package bench;

import java.util.function.Consumer;

final class Arrs {

	private Arrs() {}

	static Arr create(String impl) {
		switch (impl) {
			case "plain":
				return new PlainArr();
			case "synclist":
				return new SyncListArr();
			case "stamped":
				return new StampedArr();
			case "deque":
				return new DequeArr();
			default:
				throw new IllegalArgumentException("unknown impl [" + impl + "]");
		}
	}

	static Object[] values(int n) {
		Object[] v = new Object[n];
		for (int i = 0; i < n; i++) {
			v[i] = Integer.valueOf(i);
		}
		return v;
	}

	static Arr filled(String impl, int n) {
		Arr a = create(impl);
		for (Object v: values(n)) {
			a.append(v);
		}
		return a;
	}

	// a for-in body: sums the elements, so the walk can't be dead-code eliminated
	static final class Sum implements Consumer<Object> {
		int acc;

		@Override
		public void accept(Object o) {
			acc += (Integer) o;
		}
	}
}
