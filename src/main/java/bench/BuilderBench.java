package bench;

import java.util.Arrays;
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
 * Java-side construction (deserializeJson, listToArray, Caster, query-to-array): an append loop against the unpublished
 * builder, which fills a plain Object[] and hands it to DequeArr.of with no lock ops. The array escapes (returned),
 * as a BIF result does.
 *
 * builderExact knows the size up front (Caster from a Java array, query column). builderGrow doesn't (the JSON parser,
 * a list split), so it grows by ArrayList's 1.5x and hands over the oversized array untrimmed.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
@State(Scope.Thread)
public class BuilderBench {

	@Param({ "10", "1000", "100000" })
	int size;

	Object[] values;

	@Setup
	public void setup() {
		values = Arrs.values(size);
	}

	// today: synchronizedList add per element
	@Benchmark
	public Arr appendSynclist() {
		SyncListArr a = new SyncListArr();
		for (Object v: values) {
			a.append(v);
		}
		return a;
	}

	// CFML-side append loop on the deque: one thin-lock CAS per element
	@Benchmark
	public Arr appendDeque() {
		DequeArr a = new DequeArr();
		for (Object v: values) {
			a.append(v);
		}
		return a;
	}

	@Benchmark
	public Arr builderExact() {
		Object[] d = new Object[values.length];
		int n = 0;
		for (Object v: values) {
			d[n++] = v;
		}
		return DequeArr.of(d, n);
	}

	@Benchmark
	public Arr builderGrow() {
		Object[] d = new Object[10];
		int n = 0;
		for (Object v: values) {
			if (n == d.length) d = Arrays.copyOf(d, n + (n >> 1));
			d[n++] = v;
		}
		return DequeArr.of(d, n);
	}
}
