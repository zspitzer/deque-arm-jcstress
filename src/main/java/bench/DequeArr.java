package bench;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The spec design: a deque with lock-free validated reads and a lazy writer lock.
 *
 * Readers never lock: they load the current {@link Holder}, its {@code epoch}, its packed {@code bounds}, then index
 * {@code data} and re-check the epoch, retrying if it was odd or moved (a seqlock read, as StampedLock.validate). Walks
 * are live: one validated point read per step, the semantics ListIteratorImpl has today.
 *
 * Writers serialise on the lock. Ops that touch only one end slot need no epoch change: append past {@code end},
 * prepend before {@code head}, and pop / shift by moving a bound and tombstoning the slot it uncovered. Store order on
 * removal is bounds first, then the tombstone, both release; a reader that loaded the old bounds and lands on the
 * tombstone retries, and its acquire of the tombstone guarantees the retry sees the new bounds.
 *
 * A write into a reused (tombstoned) slot bumps the epoch by 2 first, since a reader holding the old bounds would map
 * that slot to a different key. A middle delete shifts the shorter side in place, as ArrayDeque.delete does: the epoch
 * goes odd with a storeStoreFence before the shift, as StampedLock's write acquire, and back to even with a release
 * store after it. Growth and sort still copy into a new array and publish a new holder.
 */
final class DequeArr implements Arr {

	static final Object TOMBSTONE = new Object();
	// read() result for a key outside the bounds
	private static final Object END = new Object();
	private static final int BULK_TRIES = 4;
	// negative control for jcstress only: -Ddeque.novalidate=true skips the epoch checks, to prove the middle-shift
	// tests can see a torn read. A static final, so it folds away when off
	static final boolean NO_VALIDATE = Boolean.getBoolean("deque.novalidate");
	// negative control for jcstress only: -Ddeque.fillnofence=true leaves fill's slot stores plain after the epoch bump,
	// as first ported to ArrayImpl (LDEV-6811), to show the reuse-by-fill tests can see the reordering on ARM
	static final boolean FILL_NO_FENCE = Boolean.getBoolean("deque.fillnofence");
	private static final Object[] EMPTY = {};
	private static final int DEFAULT_CAP = 10;
	private static final int SPINS = 64;

	static final class Holder {
		final Object[] data;
		// head in the high 32 bits, end in the low 32, so a reader gets a consistent pair from one load
		volatile long bounds;
		// even when stable, odd while a multi-slot shift is in progress; +2 before a write into a reused slot. See the
		// class comment
		volatile int epoch;

		Holder(Object[] data, int head, int end) {
			this.data = data;
			this.bounds = pack(head, end);
		}
	}

	private static final VarHandle AA = MethodHandles.arrayElementVarHandle(Object[].class);
	private static final VarHandle BOUNDS;
	private static final VarHandle EPOCH;
	private static final VarHandle OWNER;
	private static final VarHandle LOCK;

	static {
		try {
			MethodHandles.Lookup l = MethodHandles.lookup();
			BOUNDS = l.findVarHandle(Holder.class, "bounds", long.class);
			EPOCH = l.findVarHandle(Holder.class, "epoch", int.class);
			OWNER = l.findVarHandle(DequeArr.class, "owner", Thread.class);
			LOCK = l.findVarHandle(DequeArr.class, "lock", ReentrantLock.class);
		}
		catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private volatile Holder h = new Holder(EMPTY, 0, 0);

	// lazy writer lock: a thin CAS lock on owner, inflated to a ReentrantLock the first time a second writer contends
	private volatile Thread owner;
	private int holds;
	private boolean viaLock;
	private volatile ReentrantLock lock;

	// unpublished builder: takes ownership of data[ 0, n ) with no lock ops; the volatile store of h publishes the elements
	static DequeArr of(Object[] data, int n) {
		DequeArr a = new DequeArr();
		a.h = new Holder(data, 0, n);
		return a;
	}

	static long pack(int head, int end) {
		return ((long) head << 32) | (end & 0xFFFFFFFFL);
	}

	static int head(long b) {
		return (int) (b >>> 32);
	}

	static int end(long b) {
		return (int) b;
	}

	// ---- readers ----

	// a validated point read: the element at key, or END when key is outside the bounds. The out-of-range answer is
	// validated too, so a reader mid-shift retries rather than reporting a size that only existed inside the shift
	private Object read(int key) {
		while (true) {
			Holder x = h;
			int e = x.epoch;
			if ((e & 1) != 0 && !NO_VALIDATE) {
				Thread.onSpinWait();
				continue;
			}
			long b = x.bounds;
			int head = head(b), n = end(b) - head, idx;
			if (key > 0) idx = key > n ? -1 : head + key - 1;
			else idx = key == 0 || n + key < 0 ? -1 : head + n + key;
			// the slot load is an acquire, and so is the bounds load, so the epoch re-read can't float above either
			Object v = idx < 0 ? END : AA.getAcquire(x.data, idx);
			if (v != TOMBSTONE && (NO_VALIDATE || x.epoch == e)) return v;
		}
	}

	@Override
	public Object get(int key) {
		Object v = read(key);
		if (v == END) throw Arr.outOfRange(key, size());
		return v;
	}

	@Override
	public int size() {
		long b = h.bounds;
		return end(b) - head(b);
	}

	@Override
	public Object first() {
		Object v = read(1);
		if (v == END) throw Arr.empty();
		return v;
	}

	// live walk, one validated point read per step, no lock held while the action runs
	@Override
	public void forEach(Consumer<Object> action) {
		for (int key = 1;; key++) {
			Object v = read(key);
			if (v == END) return;
			action.accept(v);
		}
	}

	// bulk reads scan with plain or acquire loads and validate the epoch after, retrying a few times before scanning
	// under the writer lock, so steady middle deletes can't starve them
	@Override
	public int indexOf(Object value) {
		for (int tries = 0; tries < BULK_TRIES; tries++) {
			Holder x = h;
			int e = x.epoch;
			if ((e & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			long b = x.bounds;
			int found = scan(x.data, head(b), end(b), value);
			if (x.epoch == e) return found;
		}
		lock();
		try {
			long b = h.bounds;
			return scan(h.data, head(b), end(b), value);
		}
		finally {
			unlock();
		}
	}

	private static int scan(Object[] d, int head, int end, Object value) {
		for (int i = head; i < end; i++) {
			if (value.equals(AA.getAcquire(d, i))) return i - head + 1;
		}
		return 0;
	}

	@Override
	public Object[] toArray() {
		for (int tries = 0; tries < BULK_TRIES; tries++) {
			Holder x = h;
			int e = x.epoch;
			if ((e & 1) != 0 && !NO_VALIDATE) {
				Thread.onSpinWait();
				continue;
			}
			long b = x.bounds;
			int head = head(b), n = end(b) - head;
			Object[] out = new Object[n];
			System.arraycopy(x.data, head, out, 0, n);
			// the copy's loads are plain, so fence them before the re-read, as StampedLock.validate does
			VarHandle.acquireFence();
			// a tombstone inside a stable epoch is a concurrent pop / shift at an end; retry for a clean copy
			if ((NO_VALIDATE || x.epoch == e) && !hasTombstone(out)) return out;
		}
		lock();
		try {
			long b = h.bounds;
			return Arrays.copyOfRange(h.data, head(b), end(b));
		}
		finally {
			unlock();
		}
	}

	private static boolean hasTombstone(Object[] a) {
		for (Object o: a) {
			if (o == TOMBSTONE) return true;
		}
		return false;
	}

	// ---- writers ----

	// the comparator is user code, so it runs unlocked on a private copy, published only if nothing changed meanwhile;
	// any change to the live contents moves the holder, the bounds or the epoch. On a conflict sort once more under the
	// lock, rather than retrying unbounded against steady writers or losing their writes
	@Override
	public void sort(Comparator<Object> comp) {
		Holder x;
		long b;
		int e;
		Object[] nd;
		lock();
		try {
			x = h;
			e = x.epoch;
			b = x.bounds;
			nd = liveCopy(x, b);
		}
		finally {
			unlock();
		}
		int n = end(b) - head(b);
		Arrays.sort(nd, 0, n, comp);
		lock();
		try {
			if (h != x || x.bounds != b || x.epoch != e) {
				x = h;
				b = x.bounds;
				nd = liveCopy(x, b);
				n = end(b) - head(b);
				Arrays.sort(nd, 0, n, comp);
			}
			h = new Holder(nd, 0, n);
		}
		finally {
			unlock();
		}
	}

	// the live elements at the front of a new array with the holder's capacity
	private static Object[] liveCopy(Holder x, long b) {
		int head = head(b), n = end(b) - head;
		Object[] nd = new Object[Math.max(x.data.length, n)];
		System.arraycopy(x.data, head, nd, 0, n);
		return nd;
	}

	@Override
	public void append(Object value) {
		lock();
		try {
			Holder x = h;
			long b = x.bounds;
			int head = head(b), end = end(b);
			if (end == x.data.length) {
				int n = end - head;
				x = grow(x, head, end, 0, slack(n));
				head = 0;
				end = n;
			}
			else if (x.data[end] == TOMBSTONE) EPOCH.setRelease(x, x.epoch + 2);
			AA.setRelease(x.data, end, value);
			BOUNDS.setRelease(x, pack(head, end + 1));
		}
		finally {
			unlock();
		}
	}

	@Override
	public void prepend(Object value) {
		lock();
		try {
			Holder x = h;
			long b = x.bounds;
			int head = head(b), end = end(b);
			if (head == 0) {
				int n = end - head, front = slack(n);
				x = grow(x, head, end, front, x.data.length - end);
				head = front;
				end = front + n;
			}
			else if (x.data[head - 1] == TOMBSTONE) EPOCH.setRelease(x, x.epoch + 2);
			AA.setRelease(x.data, head - 1, value);
			BOUNDS.setRelease(x, pack(head - 1, end));
		}
		finally {
			unlock();
		}
	}

	@Override
	public Object pop() {
		lock();
		try {
			Holder x = h;
			long b = x.bounds;
			int head = head(b), end = end(b);
			if (end == head) throw Arr.empty();
			return removeLast(x, head, end);
		}
		finally {
			unlock();
		}
	}

	@Override
	public Object deleteAt(int key) {
		lock();
		try {
			Holder x = h;
			long b = x.bounds;
			int head = head(b), end = end(b), n = end - head;
			if (key < 1 || key > n) throw Arr.outOfRange(key, n);
			if (key == 1) {
				Object v = x.data[head];
				BOUNDS.setRelease(x, pack(head + 1, end));
				AA.setRelease(x.data, head, TOMBSTONE);
				return v;
			}
			if (key == n) return removeLast(x, head, end);
			// middle: shift the shorter side in place, as ArrayDeque.delete, inside an odd epoch
			Object[] d = x.data;
			int i = head + key - 1, e = x.epoch, front = key - 1, back = n - key;
			Object v = d[i];
			EPOCH.setOpaque(x, e + 1);
			// the odd epoch must be visible before any shifted slot, as StampedLock.tryAcquireWrite
			VarHandle.storeStoreFence();
			if (front < back) {
				System.arraycopy(d, head, d, head + 1, front);
				BOUNDS.setRelease(x, pack(head + 1, end));
				AA.setRelease(d, head, TOMBSTONE);
			}
			else {
				System.arraycopy(d, i + 1, d, i, back);
				BOUNDS.setRelease(x, pack(head, end - 1));
				AA.setRelease(d, end - 1, TOMBSTONE);
			}
			EPOCH.setRelease(x, e + 2);
			return v;
		}
		finally {
			unlock();
		}
	}

	private static Object removeLast(Holder x, int head, int end) {
		Object v = x.data[end - 1];
		BOUNDS.setRelease(x, pack(head, end - 1));
		AA.setRelease(x.data, end - 1, TOMBSTONE);
		return v;
	}

	// insert before key, 1 .. size + 1, as ArrayImpl.doInsert: the ends append / prepend, a middle insert shifts the
	// shorter side outward into the slack inside an odd epoch, or copies into a new grown holder when there's none
	void insert(int key, Object value) {
		lock();
		try {
			Holder x = h;
			long b = x.bounds;
			int head = head(b), end = end(b), n = end - head;
			if (key < 1 || key > n + 1) throw Arr.outOfRange(key, n);
			if (key == n + 1 || key == 1) {
				if (key == 1) prepend(value);
				else append(value);
				return;
			}
			Object[] d = x.data;
			int front = key - 1, back = n - front;
			boolean left = head > 0 && (front <= back || end == d.length);
			if (!left && end == d.length) {
				Object[] nd = new Object[Math.max(d.length, n + slack(n))];
				System.arraycopy(d, head, nd, 0, front);
				nd[front] = value;
				System.arraycopy(d, head + front, nd, front + 1, back);
				h = new Holder(nd, 0, n + 1);
				return;
			}
			int e = x.epoch;
			EPOCH.setOpaque(x, e + 1);
			// the odd epoch must be visible before any shifted slot, as StampedLock.tryAcquireWrite
			VarHandle.storeStoreFence();
			if (left) {
				System.arraycopy(d, head, d, head - 1, front);
				AA.setRelease(d, head - 1 + front, value);
				BOUNDS.setRelease(x, pack(head - 1, end));
			}
			else {
				int i = head + front;
				System.arraycopy(d, i, d, i + 1, back);
				AA.setRelease(d, i, value);
				BOUNDS.setRelease(x, pack(head, end + 1));
			}
			EPOCH.setRelease(x, e + 2);
		}
		finally {
			unlock();
		}
	}

	// arr[ key ] = value; past the end pads with null and appends as one op, as ArrayImpl.doPut
	void set(int key, Object value) {
		if (key < 1) throw Arr.outOfRange(key, size());
		lock();
		try {
			Holder x = h;
			long b = x.bounds;
			int head = head(b), end = end(b);
			if (key <= end - head) {
				EPOCH.setRelease(x, x.epoch + 2);
				AA.setRelease(x.data, head + key - 1, value);
			}
			else fill(x, head, end, key, value);
		}
		finally {
			unlock();
		}
	}

	// grow only, null padded, as ArrayImpl.resize
	void resize(int to) {
		lock();
		try {
			Holder x = h;
			long b = x.bounds;
			int head = head(b), end = end(b);
			if (to > end - head) fill(x, head, end, to, null);
		}
		finally {
			unlock();
		}
	}

	// grow the array to newN elements, null padded, with last in the final slot, as ArrayImpl.fill
	private void fill(Holder x, int head, int end, int newN, Object last) {
		int n = end - head;
		Object[] d = x.data;
		if (head + newN > d.length) {
			x = grow(x, head, end, 0, Math.max(Math.max(d.length, n + slack(n)), newN) - n);
			head = 0;
			end = n;
			d = x.data;
		}
		else {
			// a reader holding older, wider bounds would map a reused slot to a different key
			for (int i = end; i < head + newN; i++) {
				if (d[i] == TOMBSTONE) {
					EPOCH.setRelease(x, x.epoch + 2);
					// the slot stores below are plain, so the bump must be visible before any of them
					if (!FILL_NO_FENCE) VarHandle.storeStoreFence();
					break;
				}
			}
		}
		int newEnd = head + newN;
		for (int i = end; i < newEnd - 1; i++) {
			d[i] = null;
		}
		d[newEnd - 1] = last;
		BOUNDS.setRelease(x, pack(head, newEnd));
	}

	// ArrayList's sequence: 10 first, then 1.5x (10, 15, 22, 33 ...), so a request-local build allocates the same arrays
	private static int slack(int n) {
		return n == 0 ? DEFAULT_CAP : Math.max(n >> 1, 1);
	}

	// copy the live elements into a new array with the given slack at each end, publish it and return it
	private Holder grow(Holder x, int head, int end, int front, int back) {
		int n = end - head;
		Object[] nd = new Object[front + n + back];
		System.arraycopy(x.data, head, nd, front, n);
		Holder y = new Holder(nd, front, front + n);
		h = y;
		return y;
	}

	// ---- lazy lock ----

	private void lock() {
		Thread t = Thread.currentThread();
		if (owner == t) {
			holds++;
			return;
		}
		if (lock == null && OWNER.compareAndSet(this, null, t)) {
			holds = 1;
			return;
		}
		slowLock(t);
	}

	private void slowLock(Thread t) {
		ReentrantLock l = lock;
		if (l == null) {
			for (int i = 0; i < SPINS; i++) {
				Thread.onSpinWait();
				if (owner == null && OWNER.compareAndSet(this, null, t)) {
					holds = 1;
					return;
				}
			}
			l = new ReentrantLock();
			if (!LOCK.compareAndSet(this, null, l)) l = lock;
		}
		// once inflated, contended writers park here instead of spinning; only fast-path writers already
		// in flight when the lock was installed can still hold the thin lock, so this spin is bounded
		l.lock();
		int spins = 0;
		while (!OWNER.compareAndSet(this, null, t)) {
			if (++spins < 1024) Thread.onSpinWait();
			else Thread.yield();
		}
		holds = 1;
		viaLock = true;
	}

	private void unlock() {
		if (--holds > 0) return;
		boolean rl = viaLock;
		viaLock = false;
		OWNER.setRelease(this, null);
		if (rl) lock.unlock();
	}

	boolean inflated() {
		return lock != null;
	}
}
