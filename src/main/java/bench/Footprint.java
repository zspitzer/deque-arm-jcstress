package bench;

import org.openjdk.jol.info.GraphLayout;

/**
 * Retained bytes per array for each impl at 0, 1 and 10 elements, excluding the elements themselves (cached Integers,
 * shared by every impl). deque is reported before and after lazy-lock inflation.
 */
public final class Footprint {

	public static void main(String[] args) throws Exception {
		System.out.println(Runtime.version());
		String[] impls = { "plain", "synclist", "stamped", "deque" };
		int[] sizes = { 0, 1, 10 };
		System.out.printf("%-16s %8s %8s %8s%n", "impl", "0", "1", "10");
		for (String impl: impls) {
			System.out.printf("%-16s", impl);
			for (int n: sizes) {
				System.out.printf(" %8d", bytes(Arrs.filled(impl, n), n));
			}
			System.out.println();
		}
		System.out.printf("%-16s", "deque inflated");
		for (int n: sizes) {
			DequeArr a = inflated();
			for (Object v: Arrs.values(n)) {
				a.append(v);
			}
			System.out.printf(" %8d", bytes(a, n));
		}
		System.out.println();
		System.out.println();
		System.out.println(GraphLayout.parseInstance(Arrs.filled("deque", 1)).toFootprint());
		System.out.println(GraphLayout.parseInstance(Arrs.filled("synclist", 1)).toFootprint());
	}

	private static long bytes(Arr a, int n) {
		long elements = n == 0 ? 0 : GraphLayout.parseInstance(Arrs.values(n)).totalSize() - GraphLayout.parseInstance((Object) new Object[n]).totalSize();
		return GraphLayout.parseInstance(a).totalSize() - elements;
	}

	// force the lazy lock to inflate: hold the thin lock on another thread while this one appends
	private static DequeArr inflated() throws InterruptedException {
		DequeArr a = new DequeArr();
		Thread[] ts = new Thread[8];
		for (int i = 0; i < ts.length; i++) {
			ts[i] = new Thread(() -> {
				for (int j = 0; j < 20000 && !a.inflated(); j++) {
					a.append(j);
					a.pop();
				}
			});
			ts[i].start();
		}
		for (Thread t: ts) {
			t.join();
		}
		if (!a.inflated()) throw new IllegalStateException("lock did not inflate");
		return a;
	}
}
