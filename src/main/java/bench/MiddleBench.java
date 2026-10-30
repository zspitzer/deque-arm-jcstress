package bench;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Middle deletes, the copy-and-publish op that real code hits: Preside's arrayDelete( fields, field ) inside a for-in
 * (LDEV-1057). ArrayList shifts the tail in place with no allocation; the deque publishes a new array per delete. Each
 * op deletes from the middle then appends the element back, so the depth stays at size. Request-local, one array per
 * thread.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
@State(Scope.Thread)
public class MiddleBench {

	@Param({ "plain", "synclist", "deque" })
	String impl;

	@Param({ "10", "1000", "100000" })
	int size;

	Arr arr;
	int mid;

	@Setup(Level.Iteration)
	public void setup() {
		arr = Arrs.filled(impl, size);
		mid = size / 2 + 1;
	}

	// arrayDeleteAt( arr, mid ), ns per delete + append
	@Benchmark
	public Object deleteAt() {
		Object v = arr.deleteAt(mid);
		arr.append(v);
		return v;
	}

	// arrayDelete( arr, value ): find it, then delete by index
	@Benchmark
	public Object deleteByValue() {
		Object v = arr.get(mid);
		arr.deleteAt(arr.indexOf(v));
		arr.append(v);
		return v;
	}
}
