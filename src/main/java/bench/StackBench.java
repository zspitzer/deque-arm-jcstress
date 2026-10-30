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
 * Wheels' front-of-array stack: ArrayPrepend( request.$wheelsInvoked, x ) ... ArrayDeleteAt( request.$wheelsInvoked, 1 ),
 * at a standing depth of size. Request-local, one array per thread.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
@State(Scope.Thread)
public class StackBench {

	private static final Object ENTRY = Integer.valueOf(-1);

	@Param({ "plain", "synclist", "stamped", "deque" })
	String impl;

	@Param({ "10", "1000", "100000" })
	int size;

	Arr arr;

	@Setup(Level.Iteration)
	public void setup() {
		arr = Arrs.filled(impl, size);
	}

	// ns per push + pop pair
	@Benchmark
	public Object pushPop() {
		arr.prepend(ENTRY);
		return arr.deleteAt(1);
	}
}
