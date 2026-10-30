package bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * Today's {@code ArrayImpl}: {@code Collections.synchronizedList( new ArrayList )}. Each operation makes the same list
 * calls {@code ListAsArray} and the BIFs make on 8.0, so it pays the same number of monitors and has the same
 * check-then-act gaps.
 */
final class SyncListArr implements Arr {

	private final List<Object> list = Collections.synchronizedList(new ArrayList<>());

	// ListAsArray.getE: 2 monitors for a positive key, up to 4 for a negative one
	@Override
	public Object get(int key) {
		if (key <= 0) {
			int idx = list.size() + key < 0 ? -1 : list.size() + key;
			if (idx < 0 || key == 0) throw Arr.outOfRange(key, list.size());
			return list.get(idx);
		}
		if (key > list.size()) throw Arr.outOfRange(key, list.size());
		return list.get(key - 1);
	}

	@Override
	public int size() {
		return list.size();
	}

	// appendEL
	@Override
	public void append(Object value) {
		list.add(value);
	}

	// ListAsArray.prepend
	@Override
	public void prepend(Object value) {
		list.add(0, value);
	}

	// ArrayFirst: size() then getE( 1 ), 3 monitors
	@Override
	public Object first() {
		if (list.size() == 0) throw Arr.empty();
		return get(1);
	}

	// ListAsArray.pop: 3 monitors
	@Override
	public Object pop() {
		if (list.size() == 0) throw Arr.empty();
		return list.remove(list.size() - 1);
	}

	// ArrayDeleteAt -> ListAsArray.removeE: 1 monitor, ArrayList shifts the tail left in place
	@Override
	public Object deleteAt(int key) {
		return list.remove(key - 1);
	}

	// ListIteratorImpl as for-in drives it: hasNext(), then next() calls hasNext() again before get(), 3 monitors per element
	@Override
	public void forEach(Consumer<Object> action) {
		int index = -1;
		while (list.size() > index + 1) {
			if (!(list.size() > index + 1)) throw new NoSuchElementException();
			action.accept(list.get(++index));
		}
	}

	// ArraySupport.remove( Object ) -> ListAsArray.indexOf: 1 monitor
	@Override
	public int indexOf(Object value) {
		return list.indexOf(value) + 1;
	}

	// ListAsArray.sortIt -> SynchronizedList.sort: the whole sort, comparator included, under the monitor
	@Override
	public void sort(Comparator<Object> comp) {
		Collections.sort(list, comp);
	}

	// ListAsArray.toArray: 1 monitor
	@Override
	public Object[] toArray() {
		return list.toArray();
	}
}
