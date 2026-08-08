package com.orderbook.engine.benchmark;

import com.orderbook.engine.strategy.BSTOrderBook;
import com.orderbook.engine.strategy.LinkedListOrderBook;
import com.orderbook.engine.strategy.OrderBook;

/** JMH @Param-friendly registry of the implementations under comparison. */
public enum OrderBookImplementation {
    LINKED_LIST {
        @Override
        public OrderBook create() {
            return new LinkedListOrderBook();
        }
    },
    BST {
        @Override
        public OrderBook create() {
            return new BSTOrderBook();
        }
    };

    public abstract OrderBook create();
}
