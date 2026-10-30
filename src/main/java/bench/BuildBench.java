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
 * Request-local accumulator: arr = [], then arrayAppend( arr, x ) in a loop. Every thread builds its own array, so
 * -t 32 measures allocation scaling, not contention. The array escapes (it's returned), as a real one lands in a scope.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
@State(Scope.Thread)
public class BuildBench {

	@Param({ "plain", "synclist", "stamped", "deque" })
	String impl;

	@Param({ "10", "1000", "100000" })
	int size;

	Object[] values;

	@Setup
	public void setup() {
		values = Arrs.values(size);
	}

	// ns per whole build
	@Benchmark
	public Arr appendLoop() {
		Arr a = Arrs.create(impl);
		for (Object v: values) {
			a.append(v);
		}
		return a;
	}
}
