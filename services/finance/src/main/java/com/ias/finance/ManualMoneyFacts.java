package com.ias.finance;

import java.sql.*;
import java.util.*;
import static com.ias.finance.JdbcMoneyLedger.*;

/** Same-transaction immutable staff assertion. It does not claim a payment-channel integration. */
final class ManualMoneyFacts {
 private ManualMoneyFacts(){}
 static void append(Connection db,JdbcFeeGroups.Actor actor,FeeGroup.Identity owner,UUID operation,Command command)throws SQLException{
  UUID business,original;long amount;MoneyLedger.Voucher voucher;String movement;
  if(command instanceof Collect c){business=c.payment().id();original=c.payment().correctionOf();amount=c.payment().cents();voucher=c.payment().voucher();movement="RECEIVED";}
  else if(command instanceof RefundPaid c){business=c.refund().id();original=c.refund().originalPaymentId();amount=c.refund().cents();voucher=c.refund().voucher();movement="REFUNDED";}
  else if(command instanceof Rebook c){business=c.replacement().id();original=c.originalPaymentId();amount=c.replacement().cents();voucher=c.replacement().voucher();movement="REBOOKED";}
  else return;
  execute(db,"INSERT INTO finance_manual_money_fact VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",owner.tenantId(),operation.toString(),owner.faultGroupId().toString(),actor.userId(),business.toString(),movement,original==null?null:original.toString(),amount,voucher.reference(),java.sql.Date.valueOf(voucher.date()),voucher.method(),voucher.privateFileId().toString(),voucher.evidenceDigest(),digest(List.of(owner,action(command),command)),JSON.writeValueAsString(command),stamp());
 }
}
