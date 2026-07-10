package io.github.ajayaj724.tradecore.risk;

/** Account cash in paise: settled postings, active order holds, and what remains spendable. */
record CashBalance(String account, long settled, long held, long available) {}
