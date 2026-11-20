package com.ias.ai;

import java.math.*;
import java.util.*;

/** Deterministic admission rules. This model does NOT call a provider or persist a real billing ledger. */
public final class BudgetAdmission {
    private BudgetAdmission(){}
    public enum State{RESERVED,UNKNOWN,SETTLED,RELEASED}
    public record Price(String modelId,String configVersion,String priceVersion,String currency,
                        BigDecimal inputPerMillion,BigDecimal outputPerMillion){
        public Price{for(String value:new String[]{modelId,configVersion,priceVersion,currency})if(value==null||value.isBlank())throw invalid("PRICE_REQUIRED");money(inputPerMillion);money(outputPerMillion);if(!currency.matches("[A-Z]{3}"))throw invalid("INVALID_CURRENCY");}
    }
    public record Bound(Price price,long maxInputTokens,long maxOutputTokens,int maxBillableAttempts,BigDecimal maximum){
        public Bound {Objects.requireNonNull(price);if(maxInputTokens<1||maxOutputTokens<1||maxBillableAttempts<1||maxBillableAttempts>10)throw invalid("BOUND_REQUIRED");money(maximum);BigDecimal expected=calculate(price,maxInputTokens,maxOutputTokens,maxBillableAttempts);if(maximum.compareTo(expected)!=0)throw invalid("BOUND_MISMATCH");maximum=expected;}
    }
    public record Reservation(UUID operationId,String tenantId,String actorId,String budgetId,Bound bound,
                              State state,BigDecimal settledCost){
        public Reservation{Objects.requireNonNull(operationId);Objects.requireNonNull(bound);Objects.requireNonNull(state);for(String id:new String[]{tenantId,actorId,budgetId})if(id==null||id.isBlank())throw invalid("IDENTITY_REQUIRED");if(state==State.SETTLED){money(settledCost);}else if(settledCost!=null)throw invalid("INVALID_STATE");}
    }
    public interface CurrentAuthorization {boolean allowed(String tenantId,String actorId,String budgetId);}
    public static Bound bound(Price price,long maxInput,long maxOutput,int maxAttempts){return new Bound(price,maxInput,maxOutput,maxAttempts,calculate(price,maxInput,maxOutput,maxAttempts));}
    private static BigDecimal calculate(Price p,long input,long output,int attempts){
        Objects.requireNonNull(p);if(input<1||output<1||attempts<1||attempts>10)throw invalid("BOUND_REQUIRED");
        return p.inputPerMillion().multiply(BigDecimal.valueOf(input)).add(p.outputPerMillion().multiply(BigDecimal.valueOf(output))).multiply(BigDecimal.valueOf(attempts)).divide(BigDecimal.valueOf(1_000_000),6,RoundingMode.CEILING);
    }
    private static void money(BigDecimal amount){if(amount==null||amount.signum()<0||amount.precision()>30||amount.scale()>12)throw invalid("INVALID_AMOUNT");}
    private static IllegalArgumentException invalid(String code){return new IllegalArgumentException(code);}
    /** In-memory reference used by tests only. Real provider admission MUST wait for a durable atomic adapter. */
    public static final class ReferenceLedger {
        private final String tenant,budget,currency;private final BigDecimal limit;private final CurrentAuthorization authorization;
        private BigDecimal held=BigDecimal.ZERO,spent=BigDecimal.ZERO;private final Map<UUID,Reservation> entries=new HashMap<>();
        public ReferenceLedger(String tenant,String budget,String currency,BigDecimal limit,CurrentAuthorization authorization){this.tenant=Objects.requireNonNull(tenant);this.budget=Objects.requireNonNull(budget);this.currency=Objects.requireNonNull(currency);money(limit);this.limit=limit;this.authorization=Objects.requireNonNull(authorization);}
        private void authorized(String tenant,String actor){if(!this.tenant.equals(tenant)||!authorization.allowed(tenant,actor,budget))throw invalid("ACCESS_DENIED");}
        public synchronized Reservation reserve(UUID operation,String tenant,String actor,Bound bound){
            authorized(tenant,actor);Objects.requireNonNull(bound);if(!currency.equals(bound.price().currency()))throw invalid("CURRENCY_MISMATCH");
            Reservation old=entries.get(operation);if(old!=null){if(!old.tenantId().equals(tenant)||!old.actorId().equals(actor)||!old.bound().equals(bound))throw invalid("IDEMPOTENCY_CONFLICT");return old;}
            if(spent.add(held).add(bound.maximum()).compareTo(limit)>0)throw invalid("BUDGET_EXHAUSTED");
            Reservation result=new Reservation(operation,tenant,actor,budget,bound,State.RESERVED,null);entries.put(operation,result);held=held.add(bound.maximum());return result;
        }
        public synchronized Reservation unknown(UUID operation,String tenant,String actor){
            authorized(tenant,actor);Reservation old=owned(operation,actor);if(old.state()==State.SETTLED||old.state()==State.RELEASED)return old;
            Reservation result=new Reservation(old.operationId(),tenant,actor,budget,old.bound(),State.UNKNOWN,null);entries.put(operation,result);return result;
        }
        public synchronized Reservation settle(UUID operation,String tenant,String actor,BigDecimal actualCost){
            authorized(tenant,actor);money(actualCost);Reservation old=owned(operation,actor);
            if(old.state()==State.SETTLED){if(old.settledCost().compareTo(actualCost)!=0)throw invalid("SETTLEMENT_CONFLICT");return old;}
            if(old.state()==State.RELEASED)throw invalid("ALREADY_RELEASED_RECONCILE");
            // Preserve a real charge even above the estimate. Future admission then stops; do not hide an overrun.
            held=held.subtract(old.bound().maximum());spent=spent.add(actualCost);Reservation result=new Reservation(operation,tenant,actor,budget,old.bound(),State.SETTLED,actualCost);entries.put(operation,result);return result;
        }
        public synchronized Reservation releaseVerifiedNoCharge(UUID operation,String tenant,String actor,boolean providerConfirmedNoCharge){
            authorized(tenant,actor);if(!providerConfirmedNoCharge)throw invalid("BILLING_RESULT_UNKNOWN");Reservation old=owned(operation,actor);
            if(old.state()==State.RELEASED)return old;if(old.state()==State.SETTLED)throw invalid("ALREADY_SETTLED");
            held=held.subtract(old.bound().maximum());Reservation result=new Reservation(operation,tenant,actor,budget,old.bound(),State.RELEASED,null);entries.put(operation,result);return result;
        }
        private Reservation owned(UUID operation,String actor){Reservation old=entries.get(operation);if(old==null||!old.actorId().equals(actor))throw invalid("TARGET_NOT_ACCESSIBLE");return old;}
        public synchronized BigDecimal held(){return held;}public synchronized BigDecimal spent(){return spent;}
    }
}
