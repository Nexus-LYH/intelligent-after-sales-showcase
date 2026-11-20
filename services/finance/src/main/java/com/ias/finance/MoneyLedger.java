package com.ias.finance;

import java.time.LocalDate;
import java.util.*;

/** RMB cents only. Cost and approval routing are deliberately outside this ledger. */
public final class MoneyLedger {
    private MoneyLedger(){}
    public record Approval(UUID completionId,String coverageDigest,Map<UUID,Long> executedAuthorizedLines){
        public Approval{Objects.requireNonNull(completionId);text(coverageDigest,64);if(!coverageDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_COVERAGE_DIGEST");executedAuthorizedLines=Map.copyOf(executedAuthorizedLines);if(executedAuthorizedLines.isEmpty()||executedAuthorizedLines.size()>50)throw new IllegalArgumentException("INVALID_COVERAGE");executedAuthorizedLines.forEach((id,n)->{Objects.requireNonNull(id);positive(n);});}
    }
    public record Voucher(String reference,LocalDate date,String method,UUID privateFileId,String evidenceDigest){
        public Voucher{ text(reference,100);Objects.requireNonNull(date);text(method,40);Objects.requireNonNull(privateFileId);text(evidenceDigest,64);if(!evidenceDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_EVIDENCE_DIGEST");}
    }
    public record Payment(UUID id,UUID billId,long cents,Voucher voucher,UUID correctionOf){
        public Payment(UUID id,UUID billId,long cents,Voucher voucher){this(id,billId,cents,voucher,null);}
        public Payment{Objects.requireNonNull(id);Objects.requireNonNull(billId);positive(cents);Objects.requireNonNull(voucher);}
    }
    public record Refund(UUID id,UUID originalPaymentId,long cents,Voucher voucher){public Refund{Objects.requireNonNull(id);Objects.requireNonNull(originalPaymentId);positive(cents);Objects.requireNonNull(voucher);}}
    public record State(long version,Map<UUID,Long> receivableLines,Map<UUID,Approval> assessments,Map<UUID,Payment> payments,Set<UUID> reversed,Map<UUID,Refund> refunds){
        public State{if(version<0)throw new IllegalArgumentException("INVALID_VERSION");receivableLines=Map.copyOf(receivableLines);assessments=Map.copyOf(assessments);payments=Map.copyOf(payments);reversed=Set.copyOf(reversed);refunds=Map.copyOf(refunds);}
        public long receivable(){return sum(receivableLines.values());}
        public long collected(){return sum(payments.values().stream().filter(p->!reversed.contains(p.id())).map(Payment::cents).toList());}
        public long refunded(){return sum(refunds.values().stream().map(Refund::cents).toList());}
        public long netCash(){return Math.subtractExact(collected(),refunded());}
        public long billReceivable(UUID bill){var fact=assessments.get(bill);if(fact==null)throw new IllegalStateException("BILL_NOT_FOUND");return sum(fact.executedAuthorizedLines().keySet().stream().map(receivableLines::get).toList());}
        public long billNetCash(UUID bill){long paid=sum(payments.values().stream().filter(p->p.billId().equals(bill)&&!reversed.contains(p.id())).map(Payment::cents).toList());long returned=sum(refunds.values().stream().filter(r->payments.get(r.originalPaymentId()).billId().equals(bill)).map(Refund::cents).toList());return Math.subtractExact(paid,returned);}
        public long settled(){return sum(assessments.keySet().stream().map(id->Math.min(billReceivable(id),billNetCash(id))).toList());}
        public long outstanding(){return Math.subtractExact(receivable(),settled());}
        public long overpayment(){return Math.subtractExact(netCash(),settled());}
    }
    public static State empty(){return new State(0,Map.of(),Map.of(),Map.of(),Set.of(),Map.of());}
    /** Authoritative completed approval provides explicit executed, customer-authorized line coverage. */
    public static State assess(State s,long expected,UUID bill,Approval fact){
        version(s,expected);Objects.requireNonNull(bill);Objects.requireNonNull(fact);
        if(s.assessments().containsKey(bill))throw new IllegalStateException("BILL_ALREADY_ASSESSED");
        var lines=new HashMap<>(s.receivableLines());for(var entry:fact.executedAuthorizedLines().entrySet()){if(lines.putIfAbsent(entry.getKey(),entry.getValue())!=null)throw new IllegalStateException("RECEIVABLE_LINE_ALREADY_COVERED");}
        var bills=new HashMap<>(s.assessments());bills.put(bill,fact);return checked(new State(next(s),lines,bills,s.payments(),s.reversed(),s.refunds()));
    }
    /** Lowering is an explicit finance-approved adjustment, never a quote edit or a cost delta. */
    public static State lower(State s,long expected,UUID bill,UUID line,long newCents){
        version(s,expected);var approval=s.assessments().get(bill);Long old=s.receivableLines().get(line);
        if(approval==null||!approval.executedAuthorizedLines().containsKey(line)||old==null)throw new IllegalStateException("BILL_LINE_NOT_FOUND");
        if(newCents<0||newCents>=old)throw new IllegalArgumentException("INVALID_RECEIVABLE_REDUCTION");
        var lines=new HashMap<>(s.receivableLines());lines.put(line,newCents);return checked(new State(next(s),lines,s.assessments(),s.payments(),s.reversed(),s.refunds()));
    }
    public static State collect(State s,long expected,Payment p){
        version(s,expected);var bill=s.assessments().get(p.billId());if(bill==null)throw new IllegalStateException("BILL_NOT_FOUND");
        long billed=sum(bill.executedAuthorizedLines().keySet().stream().map(s.receivableLines()::get).toList());
        long paid=sum(s.payments().values().stream().filter(x->x.billId().equals(p.billId())&&!s.reversed().contains(x.id())).map(Payment::cents).toList());
        long returned=sum(s.refunds().values().stream().filter(x->s.payments().get(x.originalPaymentId()).billId().equals(p.billId())).map(Refund::cents).toList());
        // A verified offline transfer may exceed this bill. The excess is
        // retained as overpayment and is never applied to another bill.
        if(s.payments().containsKey(p.id()))throw new IllegalStateException("DUPLICATE_COLLECTION");
        if(p.correctionOf()!=null){var original=s.payments().get(p.correctionOf());if(original==null||!s.reversed().contains(original.id())||!original.voucher().reference().equals(p.voucher().reference())||s.payments().values().stream().anyMatch(x->p.correctionOf().equals(x.correctionOf())))throw new IllegalStateException("INVALID_COLLECTION_CORRECTION");}
        if(s.payments().values().stream().anyMatch(x->x.voucher().reference().equals(p.voucher().reference())&&(!s.reversed().contains(x.id())||p.correctionOf()==null)))throw new IllegalStateException("DUPLICATE_COLLECTION");
        var payments=new HashMap<>(s.payments());payments.put(p.id(),p);return checked(new State(next(s),s.receivableLines(),s.assessments(),payments,s.reversed(),s.refunds()));
    }
    /** Only a proven erroneous entry may be reversed. Real received funds require actual refund. */
    public static State reverse(State s,long expected,UUID original){
        version(s,expected);if(!s.payments().containsKey(original)||s.reversed().contains(original))throw new IllegalStateException("COLLECTION_NOT_EFFECTIVE");
        if(s.refunds().values().stream().anyMatch(r->r.originalPaymentId().equals(original)))throw new IllegalStateException("REFUNDED_COLLECTION_NOT_REVERSIBLE");
        var reversed=new HashSet<>(s.reversed());reversed.add(original);return checked(new State(next(s),s.receivableLines(),s.assessments(),s.payments(),reversed,s.refunds()));
    }
    /** Reverse and replace an erroneous nonzero entry atomically, preserving both immutable records. */
    public static State rebook(State s,long expected,UUID original,Payment replacement){
        version(s,expected);if(!original.equals(replacement.correctionOf()))throw new IllegalArgumentException("REBOOK_ORIGINAL_MISMATCH");
        State reversed=reverse(s,expected,original);State corrected=collect(reversed,reversed.version(),replacement);
        return checked(new State(next(s),corrected.receivableLines(),corrected.assessments(),corrected.payments(),corrected.reversed(),corrected.refunds()));
    }
    public static State refund(State s,long expected,Refund refund){
        version(s,expected);var payment=s.payments().get(refund.originalPaymentId());if(payment==null||s.reversed().contains(payment.id()))throw new IllegalStateException("COLLECTION_NOT_EFFECTIVE");
        long refunded=sum(s.refunds().values().stream().filter(r->r.originalPaymentId().equals(payment.id())).map(Refund::cents).toList());
        if(refund.cents()>s.overpayment()||refund.cents()>Math.max(0,Math.subtractExact(s.billNetCash(payment.billId()),s.billReceivable(payment.billId())))||refund.cents()>Math.subtractExact(payment.cents(),refunded))throw new IllegalStateException("REFUND_EXCEEDS_OVERPAYMENT");
        if(s.refunds().containsKey(refund.id())||s.refunds().values().stream().anyMatch(r->r.voucher().reference().equals(refund.voucher().reference())))throw new IllegalStateException("DUPLICATE_REFUND");
        var refunds=new HashMap<>(s.refunds());refunds.put(refund.id(),refund);return checked(new State(next(s),s.receivableLines(),s.assessments(),s.payments(),s.reversed(),refunds));
    }
    static State checked(State s){if(s.netCash()<0||s.overpayment()<0)throw new IllegalStateException("LEDGER_BALANCE_INVALID");s.receivable();return s;}
    static void version(State s,long expected){if(s.version()!=expected)throw new IllegalStateException("LEDGER_VERSION_CONFLICT");}
    static long next(State s){return Math.incrementExact(s.version());}
    static long sum(Collection<Long> values){long n=0;for(long v:values)n=Math.addExact(n,v);return n;}
    static void positive(long n){if(n<=0)throw new IllegalArgumentException("INVALID_CENTS");}
    static void text(String s,int max){if(s==null||s.isBlank()||s.length()>max||s.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("INVALID_TEXT");}
}
