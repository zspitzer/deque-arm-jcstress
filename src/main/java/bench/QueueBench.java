package bench;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * ColdBox's async log appender (AbstractAppender) in its steady state: request threads do some work and
 * queue.append( entry ), one listener thread drains with queue.first() + queue.deleteAt( 1 ) while queue.len(), and
 * something walks the queue meanwhile (preProcessQueue, serializeJson). 8 producers + 1 consumer + 1 reader.
 *
 * work is the request work between log calls (consumeCPU tokens). It has to be high enough that the consumer keeps
 * up: a run is only valid when full is ~0 and dequeued ~ appended. The first version of this cell ran 30 producers at
 * work=64, saturated at the cap, and measured nothing but writer lock handoffs. The burst case, a long backlog being
 * drained, is QueueBacklogBench.
 *
 * Read the aux counters: appended and dequeued are real ops, full / emptyPolls are the misses, walkErrors counts walks
 * that threw. The producer score is per-thread offered rate including the append, so a slower append shows as a
 * lower producer score at the same work. plain is excluded: it isn't thread safe.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
@State(Scope.Group)
public class QueueBench {

	private static final Object ENTRY = Integer.valueOf(1);
	private static final int CAP = 100_000;

	@Param({ "synclist", "stamped", "deque" })
	String impl;

	@Param({ "256", "4096" })
	int work;

	Arr queue;

	@Setup(Level.Iteration)
	public void setup() {
		queue = Arrs.create(impl);
	}

	@AuxCounters(AuxCounters.Type.OPERATIONS)
	@State(Scope.Thread)
	public static class Counts {
		public long appended;
		public long full;
		public long dequeued;
		public long emptyPolls;
		public long walked;
		public long walkErrors;

		@Setup(Level.Iteration)
		public void reset() {
			appended = full = dequeued = emptyPolls = walked = walkErrors = 0;
		}
	}

	@State(Scope.Thread)
	public static class Walker {
		final Arrs.Sum sum = new Arrs.Sum();
	}

	@Benchmark
	@Group("steady")
	@GroupThreads(8)
	public void producer(Counts c) {
		Blackhole.consumeCPU(work);
		if (queue.size() < CAP) {
			queue.append(ENTRY);
			c.appended++;
		}
		else c.full++;
	}

	@Benchmark
	@Group("steady")
	@GroupThreads(1)
	public void consumer(Counts c, Blackhole bh) {
		if (queue.size() > 0) {
			bh.consume(queue.first());
			queue.deleteAt(1);
			c.dequeued++;
		}
		else c.emptyPolls++;
	}

	@Benchmark
	@Group("steady")
	@GroupThreads(1)
	public int reader(Counts c, Walker w) {
		Arrs.Sum s = w.sum;
		s.acc = 0;
		try {
			queue.forEach(s);
			c.walked++;
		}
		catch (RuntimeException e) {
			c.walkErrors++;
		}
		return s.acc;
	}
}
