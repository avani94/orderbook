package com.orderbook.engine.strategy;

import com.orderbook.engine.model.Order;
import com.orderbook.engine.model.OrderStatus;
import com.orderbook.engine.model.OrderType;
import com.orderbook.engine.model.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioral contract every {@link OrderBook} implementation must satisfy,
 * independent of the data structure backing it. Subclasses only provide the
 * implementation under test via {@link #createOrderBook()}, so every
 * implementation is checked against the exact same scenarios.
 */
abstract class OrderBookContractTest {

    protected static final String SYMBOL = "AAPL";

    protected OrderBook book;
    protected Instant t0;

    protected abstract OrderBook createOrderBook();

    @BeforeEach
    void setUp() {
        book = createOrderBook();
        t0 = Instant.now();
    }

    protected Order order(String id, OrderType type, String price, long qty, long millisAfterT0) {
        return new Order(id, "user-1", SYMBOL, type, new BigDecimal(price), qty, t0.plusMillis(millisAfterT0));
    }

    @Test
    void restingOrderWithNoCrossUpdatesBestBidAskButProducesNoTrade() {
        List<Trade> trades = book.addOrder(order("b1", OrderType.BUY, "150.00", 100, 0));

        assertTrue(trades.isEmpty());
        assertEquals(new BigDecimal("150.00"), book.getBestBid().orElseThrow());
        assertTrue(book.getBestAsk().isEmpty());
    }

    @Test
    void crossingOrderProducesPartialFillOnAggressor() {
        book.addOrder(order("b1", OrderType.BUY, "150.00", 100, 0));
        List<Trade> trades = book.addOrder(order("s1", OrderType.SELL, "150.00", 50, 1));

        assertEquals(1, trades.size());
        Trade trade = trades.get(0);
        assertEquals("b1", trade.buyOrderId());
        assertEquals("s1", trade.sellOrderId());
        assertEquals(50L, trade.executionQuantity());
        assertEquals(new BigDecimal("150.00"), trade.executionPrice());

        assertEquals(new BigDecimal("150.00"), book.getBestBid().orElseThrow());
        assertTrue(book.getBestAsk().isEmpty());
    }

    @Test
    void exactQuantityMatchFullyFillsBothSidesAndClearsLevels() {
        book.addOrder(order("b1", OrderType.BUY, "150.00", 50, 0));
        List<Trade> trades = book.addOrder(order("s1", OrderType.SELL, "150.00", 50, 1));

        assertEquals(1, trades.size());
        assertEquals(50L, trades.get(0).executionQuantity());
        assertTrue(book.getBestBid().isEmpty());
        assertTrue(book.getBestAsk().isEmpty());
    }

    @Test
    void noMatchWhenBookIsNotCrossed() {
        book.addOrder(order("b1", OrderType.BUY, "149.00", 100, 0));
        List<Trade> trades = book.addOrder(order("s1", OrderType.SELL, "150.00", 100, 1));

        assertTrue(trades.isEmpty());
        assertEquals(new BigDecimal("149.00"), book.getBestBid().orElseThrow());
        assertEquals(new BigDecimal("150.00"), book.getBestAsk().orElseThrow());
    }

    @Test
    void priceTimePriorityFillsEarlierOrderFirstAtSamePriceLevel() {
        book.addOrder(order("s1", OrderType.SELL, "150.00", 30, 0));
        book.addOrder(order("s2", OrderType.SELL, "150.00", 30, 1));

        List<Trade> trades = book.addOrder(order("b1", OrderType.BUY, "150.00", 40, 2));

        assertEquals(2, trades.size());
        assertEquals("s1", trades.get(0).sellOrderId());
        assertEquals(30L, trades.get(0).executionQuantity());
        assertEquals("s2", trades.get(1).sellOrderId());
        assertEquals(10L, trades.get(1).executionQuantity());
    }

    @Test
    void sweepsMultiplePriceLevelsBestPriceFirst() {
        book.addOrder(order("s1", OrderType.SELL, "150.00", 20, 0));
        book.addOrder(order("s2", OrderType.SELL, "149.00", 20, 1)); // better price, should fill first
        book.addOrder(order("s3", OrderType.SELL, "151.00", 20, 2));

        List<Trade> trades = book.addOrder(order("b1", OrderType.BUY, "151.00", 45, 3));

        assertEquals(3, trades.size());
        assertEquals(new BigDecimal("149.00"), trades.get(0).executionPrice());
        assertEquals(new BigDecimal("150.00"), trades.get(1).executionPrice());
        assertEquals(new BigDecimal("151.00"), trades.get(2).executionPrice());
        assertEquals(20L, trades.get(0).executionQuantity());
        assertEquals(20L, trades.get(1).executionQuantity());
        assertEquals(5L, trades.get(2).executionQuantity());
    }

    @Test
    void cancelOrderRemovesRestingOrderFromBook() {
        book.addOrder(order("b1", OrderType.BUY, "150.00", 100, 0));

        assertTrue(book.cancelOrder("b1"));
        assertTrue(book.getBestBid().isEmpty());
        assertFalse(book.cancelOrder("b1")); // already gone
        assertFalse(book.cancelOrder("unknown"));
    }

    @Test
    void cancelledOrderIsExcludedFromFutureMatching() {
        book.addOrder(order("b1", OrderType.BUY, "150.00", 100, 0));
        book.cancelOrder("b1");

        List<Trade> trades = book.addOrder(order("s1", OrderType.SELL, "150.00", 100, 1));

        assertTrue(trades.isEmpty());
        assertEquals(new BigDecimal("150.00"), book.getBestAsk().orElseThrow());
    }

    @Test
    void orderStatusTransitionsReflectFills() {
        Order buy = order("b1", OrderType.BUY, "150.00", 100, 0);
        book.addOrder(buy);
        book.addOrder(order("s1", OrderType.SELL, "150.00", 40, 1));

        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
        assertEquals(60L, buy.getRemainingQuantity());

        book.addOrder(order("s2", OrderType.SELL, "150.00", 60, 2));

        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(0L, buy.getRemainingQuantity());
    }

    @Test
    void cancellingOneOfMultiplePriceLevelsLeavesOthersIntact() {
        book.addOrder(order("b1", OrderType.BUY, "150.00", 10, 0));
        book.addOrder(order("b2", OrderType.BUY, "151.00", 10, 1));
        book.addOrder(order("b3", OrderType.BUY, "149.00", 10, 2));

        assertTrue(book.cancelOrder("b2")); // was best bid

        assertEquals(new BigDecimal("150.00"), book.getBestBid().orElseThrow());

        List<Trade> trades = book.addOrder(order("s1", OrderType.SELL, "149.00", 25, 3));
        assertEquals(2, trades.size());
        assertEquals("b1", trades.get(0).buyOrderId());
        assertEquals("b3", trades.get(1).buyOrderId());
    }
}
