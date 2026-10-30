package bench;

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;

/**
 * The rejected optimistic-read option: {@code StampedLock} over our own {@code Object[]} + {@code size}. Point reads
 * are optimistic, writes take the write lock and shift in place like {@code ArrayList}. Because shifts happen in place
 * an unlocked walk can tear, so {@code forEach} holds the read lock for the whole walk, running the action under it.
 */
final class StampedArr implements Arr {

	private final StampedLock sl = new StampedLock();
	private Object[] data = new Object[10];
	private int size;

	private static int index(int key, int n) {
		if (key > 0) return key > n ? -1 : key - 1;
		return key == 0 || n + key < 0 ? -1 : n + key;
	}

	@Override
	public Object get(int key) {
		long stamp = sl.tryOptimisticRead();
		Object[] d = data;
		int n = size;
		int idx = index(key, n);
		Object v = idx >= 0 && idx < d.length ? d[idx] : null;
		if (sl.validate(stamp)) {
			if (idx < 0) throw Arr.outOfRange(key, n);
			return v;
		}
		stamp = sl.readLock();
		try {
			n = size;
			idx = index(key, n);
			if (idx < 0) throw Arr.outOfRange(key, n);
			return data[idx];
		}
		finally {
			sl.unlockRead(stamp);
		}
	}

	@Override
	public int size() {
		long stamp = sl.tryOptimisticRead();
		int n = size;
		if (sl.validate(stamp)) return n;
		stamp = sl.readLock();
		try {
			return size;
		}
		finally {
			sl.unlockRead(stamp);
		}
	}

	private void ensureCapacity() {
		if (size == data.length) data = Arrays.copyOf(data, size + (size >> 1) + 1);
	}

	@Override
	public void append(Object value) {
		long stamp = sl.writeLock();
		try {
			ensureCapacity();
			data[size++] = value;
		}
		finally {
			sl.unlockWrite(stamp);
		}
	}

	@Override
	public void prepend(Object value) {
		long stamp = sl.writeLock();
		try {
			ensureCapacity();
			System.arraycopy(data, 0, data, 1, size);
			data[0] = value;
			size++;
		}
		finally {
			sl.unlockWrite(stamp);
		}
	}

	@Override
	public Object first() {
		long stamp = sl.tryOptimisticRead();
		Object[] d = data;
		int n = size;
		Object v = n > 0 ? d[0] : null;
		if (sl.validate(stamp)) {
			if (n == 0) throw Arr.empty();
			return v;
		}
		stamp = sl.readLock();
		try {
			if (size == 0) throw Arr.empty();
			return data[0];
		}
		finally {
			sl.unlockRead(stamp);
		}
	}

	@Override
	public Object pop() {
		long stamp = sl.writeLock();
		try {
			if (size == 0) throw Arr.empty();
			Object v = data[--size];
			data[size] = null;
			return v;
		}
		finally {
			sl.unlockWrite(stamp);
		}
	}

	@Override
	public Object deleteAt(int key) {
		long stamp = sl.writeLock();
		try {
			if (key < 1 || key > size) throw Arr.outOfRange(key, size);
			Object v = data[key - 1];
			System.arraycopy(data, key, data, key - 1, size - key);
			data[--size] = null;
			return v;
		}
		finally {
			sl.unlockWrite(stamp);
		}
	}

	@Override
	public void forEach(Consumer<Object> action) {
		long stamp = sl.readLock();
		try {
			Object[] d = data;
			for (int i = 0, n = size; i < n; i++) {
				action.accept(d[i]);
			}
		}
		finally {
			sl.unlockRead(stamp);
		}
	}

	@Override
	public int indexOf(Object value) {
		long stamp = sl.readLock();
		try {
			for (int i = 0; i < size; i++) {
				if (value.equals(data[i])) return i + 1;
			}
			return 0;
		}
		finally {
			sl.unlockRead(stamp);
		}
	}

	@Override
	public Object[] toArray() {
		long stamp = sl.readLock();
		try {
			return Arrays.copyOf(data, size);
		}
		finally {
			sl.unlockRead(stamp);
		}
	}

	@Override
	public void sort(Comparator<Object> comp) {
		long stamp = sl.writeLock();
		try {
			Arrays.sort(data, 0, size, comp);
		}
		finally {
			sl.unlockWrite(stamp);
		}
	}
}
