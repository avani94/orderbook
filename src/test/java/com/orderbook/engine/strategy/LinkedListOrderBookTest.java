package com.orderbook.engine.strategy;

class LinkedListOrderBookTest extends OrderBookContractTest {

    @Override
    protected OrderBook createOrderBook() {
        return new LinkedListOrderBook();
    }
}
