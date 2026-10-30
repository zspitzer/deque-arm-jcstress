package bench;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Read-only, one array shared by every thread: built once, read forever (the Wheels route table). Run at -t 1 for
 * the cost every app pays, and -t 32 for the contended shared case.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
@State(Scope.Benchmark)
public class ReadBench {

	@Param({ "plain", "synclist", "stamped", "deque" })
	String impl;

	@Param({ "10", "1000", "100000" })
	int size;

	Arr arr;

	@Setup
	public void setup() {
		arr = Arrs.filled(impl, size);
	}

	@State(Scope.Thread)
	public static class Cursor {
		int key;
		final Arrs.Sum sum = new Arrs.Sum();
	}

	// one arr[ i ], cycling the keys: ns per indexed read
	@Benchmark
	public Object get(Cursor c) {
		int k = c.key + 1;
		if (k > size) k = 1;
		c.key = k;
		return arr.get(k);
	}

	// for ( i = 1; i <= arrayLen( arr ); i++ ) acc += arr[ i ]: the CFML loop shape, len in the condition. ns per whole loop
	@Benchmark
	public int indexedLoop() {
		Arr a = arr;
		int acc = 0;
		for (int i = 1; i <= a.size(); i++) {
			acc += (Integer) a.get(i);
		}
		return acc;
	}

	// for ( x in arr ) / arrayEach. ns per whole walk
	@Benchmark
	public int iterate(Cursor c) {
		Arrs.Sum s = c.sum;
		s.acc = 0;
		arr.forEach(s);
		return s.acc;
	}
}
