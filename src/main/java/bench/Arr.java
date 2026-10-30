package bench;

import java.util.Comparator;
import java.util.function.Consumer;

/**
 * The CFML-level array operations the cells exercise, 1-based like CFML. {@code get} takes negative keys from the
 * end ({@code -1} is the last element), as {@code ListAsArray.getE} does.
 */
interface Arr {

	Object get(int key);

	int size();

	void append(Object value);

	void prepend(Object value);

	Object first();

	Object pop();

	Object deleteAt(int key);

	void forEach(Consumer<Object> action);

	// 1-based key of the first element equal to value, 0 if absent; arrayDelete( arr, value ) is indexOf then deleteAt
	int indexOf(Object value);

	void sort(Comparator<Object> comp);

	// a copy of the elements, what serializeJson snapshots through (JSONConverter._serializeList)
	Object[] toArray();

	static IndexOutOfBoundsException outOfRange(int key, int size) {
		return new IndexOutOfBoundsException("Array index [" + key + "] out of range, array size is [" + size + "]");
	}

	static IllegalStateException empty() {
		return new IllegalStateException("array is empty");
	}
}
