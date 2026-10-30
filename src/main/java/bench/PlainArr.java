package bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Consumer;

/**
 * Unsynchronised {@code ArrayList}, one list call per operation: the floor. Not thread safe, so it only runs in cells
 * where nothing writes concurrently.
 */
final class PlainArr implements Arr {

	private final ArrayList<Object> list = new ArrayList<>();

	@Override
	public Object get(int key) {
		int n = list.size();
		if (key > 0) {
			if (key > n) throw Arr.outOfRange(key, n);
			return list.get(key - 1);
		}
		if (key == 0 || n + key < 0) throw Arr.outOfRange(key, n);
		return list.get(n + key);
	}

	@Override
	public int size() {
		return list.size();
	}

	@Override
	public void append(Object value) {
		list.add(value);
	}

	@Override
	public void prepend(Object value) {
		list.add(0, value);
	}

	@Override
	public Object first() {
		if (list.isEmpty()) throw Arr.empty();
		return list.get(0);
	}

	@Override
	public Object pop() {
		if (list.isEmpty()) throw Arr.empty();
		return list.remove(list.size() - 1);
	}

	@Override
	public Object deleteAt(int key) {
		int n = list.size();
		if (key < 1 || key > n) throw Arr.outOfRange(key, n);
		return list.remove(key - 1);
	}

	@Override
	public void forEach(Consumer<Object> action) {
		for (int i = 0, n = list.size(); i < n; i++) {
			action.accept(list.get(i));
		}
	}

	@Override
	public int indexOf(Object value) {
		return list.indexOf(value) + 1;
	}

	@Override
	public void sort(Comparator<Object> comp) {
		list.sort(comp);
	}

	@Override
	public Object[] toArray() {
		return list.toArray();
	}
}
