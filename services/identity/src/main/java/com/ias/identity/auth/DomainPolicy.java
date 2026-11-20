package com.ias.identity.auth;

import java.util.*;

/** Positive capabilities only; realm/type/department and object scopes are separate gates. */
public final class DomainPolicy {
    private DomainPolicy() {}
    public static final Set<String> PURPOSES=Set.of("APPROVAL_READ","APPROVAL_SUBMIT","APPROVAL_REVIEW","STOCK_CATALOG","STOCK_READ","STOCK_MANAGE","NOTIFICATION_READ","NOTIFICATION_WRITE","FINANCE_READ","FINANCE_WRITE","FEE_READ","FEE_SUBMIT","FEE_REVIEW","FEE_FINANCE","FEE_RECOVER","MONEY_READ","MONEY_WRITE");
    public static final Set<String> CAPABILITIES=Set.of("identity.employee.manage","approval.parts.read","approval.parts.submit","approval.parts.review","stock.read","stock.manage","finance.quote.read","finance.quote.write","finance.quote.confirm","approval.fees.read","approval.fees.submit","approval.fees.review","approval.fees.finance","finance.money.read","finance.money.assess","finance.money.adjust","finance.money.collect","finance.money.reverse","finance.money.refund");
    public static boolean write(String purpose){return Set.of("APPROVAL_SUBMIT","APPROVAL_REVIEW","STOCK_MANAGE","NOTIFICATION_WRITE","FINANCE_WRITE","FEE_SUBMIT","FEE_REVIEW","FEE_FINANCE","FEE_RECOVER","MONEY_WRITE").contains(purpose);}
    public static boolean allowed(String type,Collection<String> permissions,String purpose){
        if(!PURPOSES.contains(purpose))return false;
        if(purpose.startsWith("NOTIFICATION_"))return Set.of("EMPLOYEE","CUSTOMER").contains(type);
        if(purpose.startsWith("MONEY_"))return "EMPLOYEE".equals(type)&&permissions.contains("finance.money.read")&&(purpose.equals("MONEY_READ")||!Collections.disjoint(permissions,Set.of("finance.money.assess","finance.money.adjust","finance.money.collect","finance.money.reverse","finance.money.refund")));
        if(purpose.startsWith("FINANCE_"))return Set.of("EMPLOYEE","CUSTOMER").contains(type)&&permissions.contains("finance.quote.read")&&(purpose.equals("FINANCE_READ")||permissions.contains(type.equals("CUSTOMER")?"finance.quote.confirm":"finance.quote.write"));
        if(!"EMPLOYEE".equals(type))return false;
        return permissions.containsAll(switch(purpose){
            case "APPROVAL_READ" -> Set.of("approval.parts.read");
            case "APPROVAL_SUBMIT","STOCK_CATALOG" -> Set.of("approval.parts.read","approval.parts.submit","ticket.engineer.read","ticket.record");
            case "APPROVAL_REVIEW" -> Set.of("approval.parts.read","approval.parts.review","ticket.read");
            case "STOCK_READ" -> Set.of("stock.read");
            case "STOCK_MANAGE" -> Set.of("stock.read","stock.manage");
            case "FEE_READ","FEE_RECOVER" -> Set.of("approval.fees.read");
            case "FEE_SUBMIT" -> Set.of("approval.fees.read","approval.fees.submit","ticket.engineer.read","ticket.record");
            case "FEE_REVIEW" -> Set.of("approval.fees.read","approval.fees.review");
            case "FEE_FINANCE" -> Set.of("approval.fees.read","approval.fees.finance");
            default -> Set.of("__INVALID_PURPOSE__");
        });
    }
}




