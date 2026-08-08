package com.orderbook.engine.benchmark;

import com.orderbook.engine.model.Order;
import com.orderbook.engine.model.OrderType;
import com.orderbook.engine.strategy.OrderBook;
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
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cost of building a fresh order book of a given size, one order at a time,
 * with no crossing - isolates the "find or create the right price level"
 * cost (O(n) scan for the linked list, O(log n) tree descent for the BST).
 *
 * Run with: mvn compile exec:java -Dexec.mainClass="com.orderbook.engine.benchmark.BenchmarkRunner"
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
public class InsertionBenchmark {

    @Param({"LINKED_LIST", "BST"})
    public OrderBookImplementation implementation;

    @Param({"10000", "100000", "1000000"})
    public int orderCount;

    private OrderBook book;
    private List<Order> orders;

    @Setup(Level.Invocation)
    public void setup() {
        book = implementation.create();
        orders = OrderFixtures.randomOrders(orderCount, OrderType.BUY, 100.00, 200.00);
    }

    @Benchmark
    public void insertAllOrders(Blackhole blackhole) {
        for (Order order : orders) {
            blackhole.consume(book.addOrder(order));
        }
    }
}
