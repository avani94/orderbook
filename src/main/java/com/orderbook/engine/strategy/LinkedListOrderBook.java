package com.orderbook.engine.strategy;

import com.orderbook.engine.model.Order;
import com.orderbook.engine.model.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Order book backed by two hand-rolled doubly-linked lists of price levels
 * (bids sorted descending, asks sorted ascending), each level itself holding
 * a doubly-linked FIFO list of orders resting at that price.
 *
 * This is the "naive" baseline for the benchmark suite: finding or creating a
 * price level is an O(n) scan over price levels (n = number of distinct price
 * levels on that side), since there is deliberately no auxiliary index from
 * price to level. Order lookup by id (for cancellation) is O(1) via a hash
 * index, since every implementation being compared gets that same amenity.
 */
public class LinkedListOrderBook implements OrderBook {

    /** A single order sitting in a price level's FIFO queue. */
    private static final class OrderNode {
        final Order order;
        OrderNode prev;
        OrderNode next;
        PriceLevel level;

        OrderNode(Order order) {
            this.order = order;
        }
    }

    /** One price point on one side of the book; a node in the outer sorted linked list. */
    private static final class PriceLevel {
        final BigDecimal price;
        OrderNode head;
        OrderNode tail;
        PriceLevel prev;
        PriceLevel next;

        PriceLevel(BigDecimal price) {
            this.price = price;
        }

        boolean isEmpty() {
            return head == null;
        }

        void append(OrderNode node) {
            node.level = this;
            node.prev = tail;
            node.next = null;
            if (tail != null) {
                tail.next = node;
            } else {
                head = node;
            }
            tail = node;
        }

        void remove(OrderNode node) {
            if (node.prev != null) {
                node.prev.next = node.next;
            } else {
                head = node.next;
            }
            if (node.next != null) {
                node.next.prev = node.prev;
            } else {
                tail = node.prev;
            }
            node.prev = null;
            node.next = null;
        }
    }

    private PriceLevel bidHead; // best bid first, descending price
    private PriceLevel askHead; // best ask first, ascending price
    private final Map<String, OrderNode> orderIndex = new HashMap<>();

    @Override
    public List<Trade> addOrder(Order order) {
        restOrder(order);
        return matchOrders();
    }

    @Override
    public boolean cancelOrder(String orderId) {
        OrderNode node = orderIndex.remove(orderId);
        if (node == null) {
            return false;
        }
        Order order = node.order;
        if (!order.isActive()) {
            return false;
        }
        order.cancel();
        PriceLevel level = node.level;
        level.remove(node);
        if (level.isEmpty()) {
            removeLevel(level, order.isBuy());
        }
        return true;
    }

    @Override
    public List<Trade> matchOrders() {
        List<Trade> trades = new ArrayList<>();
        while (bidHead != null && askHead != null && bidHead.price.compareTo(askHead.price) >= 0) {
            trades.addAll(matchLevels(bidHead, askHead));
            if (bidHead.isEmpty()) {
                bidHead = removeLevel(bidHead, true);
            }
            if (askHead.isEmpty()) {
                askHead = removeLevel(askHead, false);
            }
        }
        return trades;
    }

    @Override
    public Optional<BigDecimal> getBestBid() {
        return bidHead == null ? Optional.empty() : Optional.of(bidHead.price);
    }

    @Override
    public Optional<BigDecimal> getBestAsk() {
        return askHead == null ? Optional.empty() : Optional.of(askHead.price);
    }

    /** Matches head orders of two crossed levels (one bid, one ask) until either drains. */
    private List<Trade> matchLevels(PriceLevel bidLevel, PriceLevel askLevel) {
        List<Trade> trades = new ArrayList<>();
        while (bidLevel.head != null && askLevel.head != null) {
            Order buyOrder = bidLevel.head.order;
            Order sellOrder = askLevel.head.order;

            long tradeQuantity = Math.min(buyOrder.getRemainingQuantity(), sellOrder.getRemainingQuantity());
            buyOrder.fill(tradeQuantity);
            sellOrder.fill(tradeQuantity);

            // Price-time priority: whichever order was resting longer sets the execution price.
            BigDecimal executionPrice = buyOrder.getTimestamp().isBefore(sellOrder.getTimestamp())
                    ? bidLevel.price
                    : askLevel.price;

            trades.add(new Trade(
                    UUID.randomUUID().toString(),
                    buyOrder.getSymbol(),
                    buyOrder.getOrderId(),
                    sellOrder.getOrderId(),
                    executionPrice,
                    tradeQuantity,
                    Instant.now()
            ));

            if (!buyOrder.isActive()) {
                orderIndex.remove(buyOrder.getOrderId());
                bidLevel.remove(bidLevel.head);
            }
            if (!sellOrder.isActive()) {
                orderIndex.remove(sellOrder.getOrderId());
                askLevel.remove(askLevel.head);
            }
        }
        return trades;
    }

    /** Inserts an order into its side's sorted level list, creating the level if needed. */
    private void restOrder(Order order) {
        boolean isBid = order.isBuy();
        PriceLevel level = findOrCreateLevel(order.getPrice(), isBid);
        OrderNode node = new OrderNode(order);
        level.append(node);
        orderIndex.put(order.getOrderId(), node);
    }

    private PriceLevel findOrCreateLevel(BigDecimal price, boolean isBid) {
        PriceLevel head = isBid ? bidHead : askHead;
        PriceLevel prev = null;
        PriceLevel cur = head;

        while (cur != null && higherPriority(cur.price, price, isBid)) {
            prev = cur;
            cur = cur.next;
        }
        if (cur != null && cur.price.compareTo(price) == 0) {
            return cur;
        }

        PriceLevel newLevel = new PriceLevel(price);
        newLevel.prev = prev;
        newLevel.next = cur;
        if (prev != null) {
            prev.next = newLevel;
        }
        if (cur != null) {
            cur.prev = newLevel;
        }
        if (prev == null) {
            if (isBid) {
                bidHead = newLevel;
            } else {
                askHead = newLevel;
            }
        }
        return newLevel;
    }

    /** True if `existing` must come before `candidate` in this side's sort order. */
    private boolean higherPriority(BigDecimal existing, BigDecimal candidate, boolean isBid) {
        int cmp = existing.compareTo(candidate);
        return isBid ? cmp > 0 : cmp < 0;
    }

    private PriceLevel removeLevel(PriceLevel level, boolean isBid) {
        PriceLevel prev = level.prev;
        PriceLevel next = level.next;
        if (prev != null) {
            prev.next = next;
        }
        if (next != null) {
            next.prev = prev;
        }
        if (isBid && level == bidHead) {
            bidHead = next;
        } else if (!isBid && level == askHead) {
            askHead = next;
        }
        level.prev = null;
        level.next = null;
        return next;
    }
}
