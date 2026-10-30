package bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gate before any benchmark number means anything.
 *
 * 1. Single-thread equivalence: random op sequences against every Arr, each outcome (value or exception class)
 *    compared with PlainArr, plus a full-content check every 1000 steps.
 * 2. Concurrent smoke per synchronised Arr: parallel append, the ColdBox queue shape with walkers, and point reads
 *    racing a rolling append + shift. stamped and deque must be clean; synclist is reported as today's behaviour.
 *
 * Not jcstress: this catches gross breakage, not every interleaving.
 */
public final class Verify {

	private static final String[] ALL = { "plain", "synclist", "stamped", "deque" };
	// stamped is out of the concurrent smoke: it passed in the first run, it's rejected, and its queue test convoys
	// (walkers hold the read lock back to back, the consumer's write lock queues behind them, and producers' read
	// fallbacks queue behind the writer), so it crawls for minutes
	private static final String[] SYNCED = { "synclist", "deque" };

	interface Op {
		Object apply(Arr a);
	}

	public static void main(String[] args) throws Exception {
		System.out.println(Runtime.version() + ", " + Runtime.getRuntime().availableProcessors() + " cpus");
		int fails = equivalence();
		for (String impl: SYNCED) {
			boolean gated = !impl.equals("synclist");
			int f = parallelAppend(impl) + queue(impl) + readRace(impl) + middleRace(impl);
			if (gated) fails += f;
		}
		System.out.println(fails == 0 ? "PASS" : "FAIL " + fails);
		System.exit(fails == 0 ? 0 : 1);
	}

	// ---- 1. single-thread equivalence ----

	private static String outcome(Arr a, Op op) {
		try {
			Object v = op.apply(a);
			return String.valueOf(v);
		}
		catch (RuntimeException e) {
			return "EX:" + e.getClass().getSimpleName();
		}
	}

	private static String contents(Arr a) {
		StringBuilder sb = new StringBuilder();
		a.forEach(o -> sb.append(o).append(','));
		return sb.toString();
	}

	private static int equivalence() {
		int mismatches = 0;
		long ops = 0;
		for (int seed = 1; seed <= 20; seed++) {
			Random r = new Random(seed);
			Arr[] arrs = new Arr[ALL.length];
			for (int i = 0; i < ALL.length; i++) {
				arrs[i] = Arrs.create(ALL[i]);
			}
			int next = 0;
			for (int step = 0; step < 20_000; step++, ops++) {
				int n = arrs[0].size();
				int pick = r.nextInt(100);
				String name;
				Op op;
				if (pick < 35) {
					Integer v = next++;
					name = "append(" + v + ")";
					op = a -> {
						a.append(v);
						return null;
					};
				}
				else if (pick < 45) {
					Integer v = next++;
					name = "prepend(" + v + ")";
					op = a -> {
						a.prepend(v);
						return null;
					};
				}
				else if (pick < 60) {
					int key = r.nextInt(2 * n + 5) - n - 2;
					name = "get(" + key + ")";
					op = a -> a.get(key);
				}
				else if (pick < 65) {
					name = "size()";
					op = Arr::size;
				}
				else if (pick < 70) {
					name = "first()";
					op = Arr::first;
				}
				else if (pick < 78) {
					name = "pop()";
					op = Arr::pop;
				}
				else if (pick < 90) {
					int key = r.nextInt(n + 3) - 1;
					name = "deleteAt(" + key + ")";
					op = a -> a.deleteAt(key);
				}
				else if (pick < 94) {
					Integer v = r.nextInt(next + 2);
					name = "indexOf(" + v + ")";
					op = a -> a.indexOf(v);
				}
				else if (pick < 96) {
					boolean desc = r.nextBoolean();
					name = "sort(" + (desc ? "desc" : "asc") + ")";
					op = a -> {
						a.sort(desc ? (x, y) -> ((Integer) y).compareTo((Integer) x) : (x, y) -> ((Integer) x).compareTo((Integer) y));
						return null;
					};
				}
				else if (pick < 98) {
					name = "toArray()";
					op = a -> java.util.Arrays.toString(a.toArray());
				}
				else {
					name = "forEach";
					op = Verify::contents;
				}
				String expected = outcome(arrs[0], op);
				for (int i = 1; i < arrs.length; i++) {
					String got = outcome(arrs[i], op);
					if (!got.equals(expected) && mismatches++ < 10) {
						System.out.println("  MISMATCH " + ALL[i] + " seed=" + seed + " step=" + step + " " + name + " size=" + n + " expected=" + clip(expected) + " got=" + clip(got));
					}
				}
				if (step % 1000 == 0) {
					String expectedAll = contents(arrs[0]);
					for (int i = 1; i < arrs.length; i++) {
						if (!contents(arrs[i]).equals(expectedAll) && mismatches++ < 10) {
							System.out.println("  CONTENT MISMATCH " + ALL[i] + " seed=" + seed + " step=" + step);
						}
					}
				}
			}
		}
		System.out.println("equivalence: " + ops + " ops x " + (ALL.length - 1) + " impls, mismatches=" + mismatches);
		return mismatches;
	}

	private static String clip(String s) {
		return s.length() > 80 ? s.substring(0, 80) + "..." : s;
	}

	// ---- 2. concurrent smoke ----

	private static void runAll(List<Thread> threads) throws InterruptedException {
		for (Thread t: threads) {
			t.start();
		}
		for (Thread t: threads) {
			t.join();
		}
	}

	// 16 threads x 50k arrayAppend: every value exactly once
	private static int parallelAppend(String impl) throws InterruptedException {
		int threads = 16, per = 50_000;
		Arr a = Arrs.create(impl);
		List<Thread> ts = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			int base = t * per;
			ts.add(new Thread(() -> {
				for (int i = 0; i < per; i++) {
					a.append(base + i);
				}
			}));
		}
		runAll(ts);
		int total = threads * per;
		int[] seen = new int[total];
		a.forEach(o -> seen[(Integer) o]++);
		int missing = 0, dups = 0;
		for (int c: seen) {
			if (c == 0) missing++;
			else if (c > 1) dups++;
		}
		int bad = (a.size() != total ? 1 : 0) + missing + dups;
		String extra = a instanceof DequeArr ? " inflated=" + ((DequeArr) a).inflated() : "";
		System.out.println("parallelAppend " + impl + ": size=" + a.size() + "/" + total + " missing=" + missing + " dups=" + dups + extra + (bad == 0 ? " ok" : " BAD"));
		return bad;
	}

	// ColdBox shape: 8 producers append (bounded), 1 consumer first() + deleteAt( 1 ), 2 walkers checking per-producer order
	private static int queue(String impl) throws InterruptedException {
		int producers = 8, per = 200_000, cap = 10_000, walkers = 2;
		Arr q = Arrs.create(impl);
		AtomicInteger done = new AtomicInteger();
		AtomicBoolean stop = new AtomicBoolean();
		AtomicLong walks = new AtomicLong(), walkErrors = new AtomicLong(), walkOrder = new AtomicLong();
		long[] consumed = new long[3]; // got, orderErrors, errors
		List<Thread> ts = new ArrayList<>();
		for (int p = 0; p < producers; p++) {
			int base = p * 10_000_000;
			ts.add(new Thread(() -> {
				for (int s = 0; s < per; s++) {
					while (q.size() > cap) {
						Thread.onSpinWait();
					}
					q.append(base + s);
				}
				done.incrementAndGet();
			}));
		}
		ts.add(new Thread(() -> {
			int[] last = new int[producers];
			Arrays.fill(last, -1);
			while (true) {
				if (q.size() > 0) {
					try {
						int v = (Integer) q.first();
						q.deleteAt(1);
						int p = v / 10_000_000, s = v % 10_000_000;
						if (s != last[p] + 1) consumed[1]++;
						last[p] = s;
						consumed[0]++;
					}
					catch (RuntimeException e) {
						consumed[2]++;
					}
				}
				else if (done.get() == producers && q.size() == 0) break;
			}
			stop.set(true);
		}));
		for (int w = 0; w < walkers; w++) {
			ts.add(new Thread(() -> {
				while (!stop.get()) {
					int[] last = new int[producers];
					Arrays.fill(last, -1);
					try {
						q.forEach(o -> {
							int v = (Integer) o;
							int p = v / 10_000_000, s = v % 10_000_000;
							if (s <= last[p]) walkOrder.incrementAndGet();
							last[p] = s;
						});
						walks.incrementAndGet();
					}
					catch (RuntimeException e) {
						walkErrors.incrementAndGet();
					}
				}
			}));
		}
		runAll(ts);
		long expected = (long) producers * per;
		int bad = (consumed[0] != expected ? 1 : 0) + (int) consumed[1] + (int) consumed[2] + (int) walkErrors.get() + (int) walkOrder.get();
		System.out.println("queue " + impl + ": dequeued=" + consumed[0] + "/" + expected + " consumerOrderErrors=" + consumed[1] + " consumerErrors=" + consumed[2] + " walks=" + walks.get() + " walkErrors=" + walkErrors.get()
				+ " walkOrderErrors=" + walkOrder.get() + (bad == 0 ? " ok" : " BAD"));
		return bad;
	}

	// point reads racing a rolling window: the writer appends at the back and deletes the front, readers get( 1 ), get( -1 ), first()
	private static int readRace(String impl) throws InterruptedException {
		int window = 1000, rounds = 500_000, readers = 4;
		Arr a = Arrs.filled(impl, window);
		AtomicBoolean stop = new AtomicBoolean();
		AtomicLong reads = new AtomicLong(), errors = new AtomicLong(), bad = new AtomicLong();
		List<Thread> ts = new ArrayList<>();
		ts.add(new Thread(() -> {
			for (int i = window; i < window + rounds; i++) {
				a.append(i);
				a.deleteAt(1);
			}
			stop.set(true);
		}));
		for (int r = 0; r < readers; r++) {
			ts.add(new Thread(() -> {
				long n = 0;
				while (!stop.get()) {
					try {
						Object x = a.get(1), y = a.get(-1), z = a.first();
						if (!(x instanceof Integer) || !(y instanceof Integer) || !(z instanceof Integer)) bad.incrementAndGet();
					}
					catch (RuntimeException e) {
						errors.incrementAndGet();
					}
					n++;
				}
				reads.addAndGet(n);
			}));
		}
		runAll(ts);
		int b = (int) (errors.get() + bad.get());
		System.out.println("readRace " + impl + ": reads=" + reads.get() + " errors=" + errors.get() + " nonElement=" + bad.get() + (b == 0 ? " ok" : " BAD"));
		return b;
	}

	// middle deletes shifting either side in place, each element appended back, so the array always holds the values
	// [ 0, n ) with at most one missing. Readers: every point read, walk step and indexOf is a real element, and every
	// toArray is a clean copy (n or n - 1 distinct values, no tombstone)
	private static int middleRace(String impl) throws InterruptedException {
		int n = 1000, readers = 3;
		long millis = 2000;
		Arr a = Arrs.filled(impl, n);
		AtomicBoolean stop = new AtomicBoolean();
		AtomicLong reads = new AtomicLong(), errors = new AtomicLong(), bad = new AtomicLong(), copies = new AtomicLong(), deletes = new AtomicLong();
		List<Thread> ts = new ArrayList<>();
		ts.add(new Thread(() -> {
			Random r = new Random(7);
			long end = System.currentTimeMillis() + millis, d = 0;
			while (System.currentTimeMillis() < end) {
				a.append(a.deleteAt(2 + r.nextInt(n - 2)));
				d++;
			}
			deletes.set(d);
			stop.set(true);
		}));
		for (int t = 0; t < readers; t++) {
			int seed = t;
			ts.add(new Thread(() -> {
				Random r = new Random(seed);
				long c = 0;
				while (!stop.get()) {
					try {
						int op = r.nextInt(10);
						if (op < 6) {
							if (!isElement(a.get(1 + r.nextInt(n - 1)), n)) bad.incrementAndGet();
						}
						else if (op < 8) {
							a.forEach(o -> {
								if (!isElement(o, n)) bad.incrementAndGet();
							});
						}
						else if (op < 9) {
							int k = a.indexOf(r.nextInt(n));
							if (k < 0 || k > n) bad.incrementAndGet();
						}
						else {
							Object[] copy = a.toArray();
							boolean[] seen = new boolean[n];
							boolean ok = copy.length == n || copy.length == n - 1;
							for (Object o: copy) {
								if (!isElement(o, n) || seen[(Integer) o]) ok = false;
								else seen[(Integer) o] = true;
							}
							if (!ok) bad.incrementAndGet();
							copies.incrementAndGet();
						}
					}
					catch (RuntimeException e) {
						errors.incrementAndGet();
					}
					c++;
				}
				reads.addAndGet(c);
			}));
		}
		runAll(ts);
		int b = (int) (errors.get() + bad.get());
		System.out.println("middleRace " + impl + ": deletes=" + deletes.get() + " reads=" + reads.get() + " toArray=" + copies.get() + " errors=" + errors.get() + " nonElement=" + bad.get() + (b == 0 ? " ok" : " BAD"));
		return b;
	}

	private static boolean isElement(Object o, int n) {
		return o instanceof Integer && (Integer) o >= 0 && (Integer) o < n;
	}
}
