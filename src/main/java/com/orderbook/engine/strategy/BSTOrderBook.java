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
 * Order book backed by two self-balancing AVL trees (bids and asks), each
 * node representing one price level and holding a doubly-linked FIFO queue
 * of orders resting at that price - same nested structure as
 * {@link LinkedListOrderBook}, but the outer index is a balanced tree
 * instead of a sorted linked list.
 *
 * Finding or creating a price level is O(log n) worst case regardless of
 * insertion order (unlike a plain unbalanced BST, which degrades to O(n) on
 * sorted input - a realistic case for prices trending in one direction).
 * Order lookup by id for cancellation is O(1) via a hash index, matching
 * {@link LinkedListOrderBook} so the two are fairly comparable.
 *
 * Best bid/ask are cached and refreshed on every structural change, so reads
 * are O(1) rather than paying an O(log n) tree-descent on every call - real
 * matching engines do the same, since best bid/ask is queried far more often
 * than the book is mutated.
 */
public class BSTOrderBook implements OrderBook {

    /** A single order sitting in a price level's FIFO queue. */
    private static final class OrderQueueNode {
        final Order order;
        OrderQueueNode prev;
        OrderQueueNode next;
        PriceLevel level;

        OrderQueueNode(Order order) {
            this.order = order;
        }
    }

    /** One price point on one side of the book; a node in the AVL tree. */
    private static final class PriceLevel {
        BigDecimal price;
        int height = 1;
        PriceLevel left;
        PriceLevel right;
        OrderQueueNode head;
        OrderQueueNode tail;

        PriceLevel(BigDecimal price) {
            this.price = price;
        }
    }

    private PriceLevel bidRoot;
    private PriceLevel askRoot;
    private PriceLevel cachedBestBid;
    private PriceLevel cachedBestAsk;
    private final Map<String, OrderQueueNode> orderIndex = new HashMap<>();

    // Side channel used by insertOrGet to hand back the found/created node
    // without needing a wrapper type; safe since this class is single-threaded.
    private PriceLevel lastTouchedLevel;

    @Override
    public List<Trade> addOrder(Order order) {
        restOrder(order);
        return matchOrders();
    }

    @Override
    public boolean cancelOrder(String orderId) {
        OrderQueueNode node = orderIndex.remove(orderId);
        if (node == null) {
            return false;
        }
        Order order = node.order;
        if (!order.isActive()) {
            return false;
        }
        order.cancel();
        PriceLevel level = node.level;
        removeFromQueue(level, node);
        if (level.head == null) {
            if (order.isBuy()) {
                bidRoot = delete(bidRoot, level.price);
                refreshBestBid();
            } else {
                askRoot = delete(askRoot, level.price);
                refreshBestAsk();
            }
        }
        return true;
    }

    @Override
    public List<Trade> matchOrders() {
        List<Trade> trades = new ArrayList<>();
        while (cachedBestBid != null && cachedBestAsk != null
                && cachedBestBid.price.compareTo(cachedBestAsk.price) >= 0) {
            trades.addAll(matchLevels(cachedBestBid, cachedBestAsk));
            if (cachedBestBid.head == null) {
                bidRoot = delete(bidRoot, cachedBestBid.price);
                refreshBestBid();
            }
            if (cachedBestAsk.head == null) {
                askRoot = delete(askRoot, cachedBestAsk.price);
                refreshBestAsk();
            }
        }
        return trades;
    }

    @Override
    public Optional<BigDecimal> getBestBid() {
        return cachedBestBid == null ? Optional.empty() : Optional.of(cachedBestBid.price);
    }

    @Override
    public Optional<BigDecimal> getBestAsk() {
        return cachedBestAsk == null ? Optional.empty() : Optional.of(cachedBestAsk.price);
    }

    /** Current height of the bid tree; exposed package-private for balance testing only. */
    int bidTreeHeight() {
        return height(bidRoot);
    }

    // ---- matching ----

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
                removeFromQueue(bidLevel, bidLevel.head);
            }
            if (!sellOrder.isActive()) {
                orderIndex.remove(sellOrder.getOrderId());
                removeFromQueue(askLevel, askLevel.head);
            }
        }
        return trades;
    }

    // ---- resting orders into the tree ----

    private void restOrder(Order order) {
        PriceLevel level;
        if (order.isBuy()) {
            bidRoot = insertOrGet(bidRoot, order.getPrice());
            level = lastTouchedLevel;
            refreshBestBid();
        } else {
            askRoot = insertOrGet(askRoot, order.getPrice());
            level = lastTouchedLevel;
            refreshBestAsk();
        }
        OrderQueueNode node = new OrderQueueNode(order);
        appendToQueue(level, node);
        orderIndex.put(order.getOrderId(), node);
    }

    private void appendToQueue(PriceLevel level, OrderQueueNode node) {
        node.level = level;
        node.prev = level.tail;
        node.next = null;
        if (level.tail != null) {
            level.tail.next = node;
        } else {
            level.head = node;
        }
        level.tail = node;
    }

    private void removeFromQueue(PriceLevel level, OrderQueueNode node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            level.head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            level.tail = node.prev;
        }
        node.prev = null;
        node.next = null;
    }

    private void refreshBestBid() {
        cachedBestBid = bidRoot == null ? null : maxNode(bidRoot);
    }

    private void refreshBestAsk() {
        cachedBestAsk = askRoot == null ? null : minNode(askRoot);
    }

    // ---- AVL mechanics ----

    private PriceLevel insertOrGet(PriceLevel node, BigDecimal price) {
        if (node == null) {
            PriceLevel created = new PriceLevel(price);
            lastTouchedLevel = created;
            return created;
        }
        int cmp = price.compareTo(node.price);
        if (cmp < 0) {
            node.left = insertOrGet(node.left, price);
        } else if (cmp > 0) {
            node.right = insertOrGet(node.right, price);
        } else {
            lastTouchedLevel = node;
            return node;
        }
        updateHeight(node);
        return rebalance(node);
    }

    private PriceLevel delete(PriceLevel node, BigDecimal price) {
        if (node == null) {
            return null;
        }
        int cmp = price.compareTo(node.price);
        if (cmp < 0) {
            node.left = delete(node.left, price);
        } else if (cmp > 0) {
            node.right = delete(node.right, price);
        } else if (node.left == null || node.right == null) {
            return node.left != null ? node.left : node.right;
        } else {
            // Two children: promote the in-order successor's price + queue into
            // this node, then delete the successor's original slot from the right subtree.
            PriceLevel successor = minNode(node.right);
            node.price = successor.price;
            node.head = successor.head;
            node.tail = successor.tail;
            for (OrderQueueNode qn = node.head; qn != null; qn = qn.next) {
                qn.level = node;
            }
            node.right = delete(node.right, successor.price);
        }
        updateHeight(node);
        return rebalance(node);
    }

    private PriceLevel minNode(PriceLevel node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private PriceLevel maxNode(PriceLevel node) {
        while (node.right != null) {
            node = node.right;
        }
        return node;
    }

    private int height(PriceLevel node) {
        return node == null ? 0 : node.height;
    }

    private void updateHeight(PriceLevel node) {
        node.height = 1 + Math.max(height(node.left), height(node.right));
    }

    private int balanceFactor(PriceLevel node) {
        return node == null ? 0 : height(node.left) - height(node.right);
    }

    private PriceLevel rebalance(PriceLevel node) {
        int balance = balanceFactor(node);
        if (balance > 1) {
            if (balanceFactor(node.left) < 0) {
                node.left = rotateLeft(node.left);
            }
            return rotateRight(node);
        }
        if (balance < -1) {
            if (balanceFactor(node.right) > 0) {
                node.right = rotateRight(node.right);
            }
            return rotateLeft(node);
        }
        return node;
    }

    private PriceLevel rotateRight(PriceLevel y) {
        PriceLevel x = y.left;
        PriceLevel t2 = x.right;
        x.right = y;
        y.left = t2;
        updateHeight(y);
        updateHeight(x);
        return x;
    }

    private PriceLevel rotateLeft(PriceLevel x) {
        PriceLevel y = x.right;
        PriceLevel t2 = y.left;
        y.left = x;
        x.right = t2;
        updateHeight(x);
        updateHeight(y);
        return y;
    }
}
