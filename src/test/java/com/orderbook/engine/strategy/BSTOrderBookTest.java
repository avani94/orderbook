package com.orderbook.engine.strategy;

import com.orderbook.engine.model.Order;
import com.orderbook.engine.model.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BSTOrderBookTest extends OrderBookContractTest {

    @Override
    protected OrderBook createOrderBook() {
        return new BSTOrderBook();
    }

    @Test
    void treeStaysBalancedUnderSortedInsertion() {
        // A plain (unbalanced) BST fed strictly increasing prices degenerates
        // into a 1000-deep linked list. An AVL tree should stay near log2(1000) ~ 10.
        BSTOrderBook bstBook = (BSTOrderBook) book;
        Instant base = Instant.now();
        for (int i = 0; i < 1000; i++) {
            BigDecimal price = BigDecimal.valueOf(10_000 + i, 2);
            bstBook.addOrder(new Order("b" + i, "user-1", SYMBOL, OrderType.BUY, price, 10, base.plusMillis(i)));
        }

        int height = bstBook.bidTreeHeight();
        assertTrue(height <= 20, "expected AVL-balanced height, got " + height + " for 1000 nodes");
    }
}
