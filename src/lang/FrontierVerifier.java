package lang;

import java.util.*;

/** Candidate cancellation-guided, certificate-producing polynomial query engine.
 * This is not equality saturation and never trusts user rewrite axioms. */
public final class FrontierVerifier {
    public enum Status { EQUAL, NOT_EQUAL, UNSUPPORTED, RESOURCE_LIMIT }
    public enum Policy { CANCELLATION, FIRST, SMALLEST, REIFIED, CERTIFIED }
    public static final class Result {
        public final Status status;
        public final String reason, certificate;
        public final long work;
        public final int steps, peakFrontier, atoms;
        private Result(Status s,String why,String proof,long work,int steps,int peak,int atoms) {
            status=s;reason=why;certificate=proof;this.work=work;this.steps=steps;peakFrontier=peak;this.atoms=atoms;
        }
    }
    public static Result check(String left,String right) { return check(left,right,10000,1000000,Policy.CERTIFIED); }
    public static Result check(String left,String right,int maxFrontier,long maxWork,Policy policy) {
        if(maxFrontier<1 || maxWork<1) throw new IllegalArgumentException("Positive budgets required");
        Engine e=new Engine(maxFrontier,maxWork,Objects.requireNonNull(policy));
        try {
            Term a=Interpreter.parseString(left).accept(e),b=Interpreter.parseString(right).accept(e);
            if(policy==Policy.CERTIFIED){Result witness=e.witness(a,b,left,right);if(witness!=null)return witness;}
            SortedMap<Mono,Rational> frontier=e.add(e.flatten(a),e.scale(e.flatten(b),Rational.MINUS_ONE));
            SortedMap<Mono,Rational> initial=new TreeMap<>(frontier);
            e.originalDefs=new ArrayList<>(e.defs);
            for(Mono m:initial.keySet())for(int id:m.powers.keySet())e.markLibrary(id);
            if(policy==Policy.REIFIED || policy==Policy.CERTIFIED){
                e.intern.clear();
                for(int id=0;id<e.defs.size();id++)if(!e.library.contains(id)){e.defs.set(id,null);e.originalDefs.set(id,null);}
            }
            e.observe(frontier.size());
            while(!frontier.isEmpty()) {
                SortedSet<Integer> eligible=new TreeSet<>();
                for(Mono m:frontier.keySet()) for(int id:m.powers.keySet()) if(e.defs.get(id).sum!=null) eligible.add(id);
                if(policy==Policy.REIFIED || policy==Policy.CERTIFIED) e.contractDefinitions(eligible);
                if(eligible.isEmpty()) return e.result(Status.NOT_EQUAL,"",left,right,initial,frontier);
                int selected=-1,bestSize=Integer.MAX_VALUE;long bestCost=Long.MAX_VALUE;
                SortedMap<Mono,Rational> bestDelta=null;
                if(policy==Policy.FIRST) selected=eligible.first();
                else if(policy==Policy.SMALLEST) {
                    for(int id:eligible) if(selected<0 || e.defs.get(id).sum.size()<e.defs.get(selected).sum.size()) selected=id;
                } else {
                    // Larger occurrence counts are considered first, but exact support decides.
                    List<Integer> order=new ArrayList<>(eligible);
                    Map<Integer,Integer> counts=new HashMap<>();
                    for(Mono m:frontier.keySet()) for(int id:m.powers.keySet()) counts.merge(id,1,Integer::sum);
                    order.sort(Comparator.<Integer>comparingInt(id -> -counts.get(id)).thenComparingInt(id -> id));
                    for(int id:order) {
                        long before=e.work;
                        SortedMap<Mono,Rational> delta=e.delta(frontier,id);
                        int size=e.resultingSize(frontier,delta);long cost=e.work-before;
                        if(size<bestSize || (size==bestSize && cost<bestCost)) {
                            bestSize=size;bestCost=cost;selected=id;bestDelta=delta;
                        }
                        if(size==0) break;
                    }
                }
                if(bestDelta==null) bestDelta=e.delta(frontier,selected);
                int resulting=e.resultingSize(frontier,bestDelta);
                e.observe(resulting);
                for(Map.Entry<Mono,Rational> change:bestDelta.entrySet()) e.insert(frontier,change.getKey(),change.getValue());
                e.trace.add(selected);
                e.actions.add(new Action("expand",selected,-1,null,null));
            }
            return e.result(Status.EQUAL,"",left,right,initial,frontier);
        } catch(Limit ex) {
            return new Result(Status.RESOURCE_LIMIT,ex.getMessage(),"",e.work,e.trace.size(),e.peak,e.defs.size());
        } catch(ParseException | TokenMgrError | UnsupportedOperationException | ArithmeticException ex) {
            return new Result(Status.UNSUPPORTED,ex.getMessage(),"",e.work,e.trace.size(),e.peak,e.defs.size());
        }
    }
    private static final class ProbeLimit extends RuntimeException {private static final long serialVersionUID=1L;}
    private static final class Limit extends RuntimeException {
        private static final long serialVersionUID=1L;
        Limit(String why){super(why);}
    }
    private static final class Mono implements Comparable<Mono> {
        final SortedMap<Integer,Integer> powers;
        Mono(Map<Integer,Integer> p){powers=Collections.unmodifiableSortedMap(new TreeMap<>(p));}
        Mono times(Mono b) {
            Map<Integer,Integer> p=new TreeMap<>(powers);
            for(Map.Entry<Integer,Integer> x:b.powers.entrySet()) {
                long degree=(long)p.getOrDefault(x.getKey(),0)+x.getValue();
                if(degree>Integer.MAX_VALUE) throw new Limit("Exponent overflow");
                p.put(x.getKey(),(int)degree);
            }
            return new Mono(p);
        }
        Mono removeOne(int id){Map<Integer,Integer> p=new TreeMap<>(powers);int n=p.get(id);if(n==1)p.remove(id);else p.put(id,n-1);return new Mono(p);}
        public int compareTo(Mono b) {
            Iterator<Map.Entry<Integer,Integer>> x=powers.entrySet().iterator(),y=b.powers.entrySet().iterator();
            while(x.hasNext()&&y.hasNext()) {Map.Entry<Integer,Integer>a=x.next(),c=y.next();int r=Integer.compare(a.getKey(),c.getKey());if(r==0)r=Integer.compare(a.getValue(),c.getValue());if(r!=0)return r;}
            return Boolean.compare(x.hasNext(),y.hasNext());
        }
        public boolean equals(Object o){return o instanceof Mono && powers.equals(((Mono)o).powers);}
        public int hashCode(){return powers.hashCode();}
    }
    private static final Mono UNIT=new Mono(Collections.emptyMap());
    private static final class Term {
        final Rational c;final Mono m;
        Term(Rational c,Mono m){this.c=c;this.m=c.isZero()?UNIT:m;}
    }
    private static final class Atom {
        final String name;final SortedMap<Mono,Rational> sum;
        Atom(String name,SortedMap<Mono,Rational> sum){this.name=name;this.sum=sum==null?null:Collections.unmodifiableSortedMap(new TreeMap<>(sum));}
        public boolean equals(Object o){if(!(o instanceof Atom))return false;Atom b=(Atom)o;return Objects.equals(name,b.name)&&Objects.equals(sum,b.sum);}
        public int hashCode(){return Objects.hash(name,sum);}
    }
    private static final class Action {
        final String op;final int factor,use;final Mono base;final Rational c;
        Action(String op,int factor,int use,Mono base,Rational c){this.op=op;this.factor=factor;this.use=use;this.base=base;this.c=c;}
        String json(Map<Integer,Integer> ids){
            StringBuilder b=new StringBuilder("{\"op\":").append(quote(op)).append(",\"factor\":").append(ids.get(factor));
            if(use>=0)b.append(",\"use\":").append(ids.get(use));
            if(base!=null){
                Map<Integer,Integer> factors=new TreeMap<>();for(Map.Entry<Integer,Integer>x:base.powers.entrySet())factors.put(ids.get(x.getKey()),x.getValue());
                SortedMap<Mono,Rational> term=new TreeMap<>();term.put(new Mono(factors),c);
                b.append(",\"basis\":\"original\",\"term\":").append(ledger(term));
            }
            return b.append('}').toString();
        }
    }
    private static final class Engine implements Visitor<Term> {
        final int cap;final long budget;final Policy policy;
        final List<Atom> defs=new ArrayList<>();final Map<Atom,Integer> intern=new HashMap<>();final List<Integer> trace=new ArrayList<>();
        List<Atom> originalDefs;final Set<Integer> library=new HashSet<>();final List<Action> actions=new ArrayList<>();
        long work;int peak;
        Engine(int cap,long budget,Policy policy){this.cap=cap;this.budget=budget;this.policy=policy;}
        void tick(){if(work>=budget)throw new Limit("Refinement work budget reached");work++;}
        void observe(int n){peak=Math.max(peak,n);if(n>cap)throw new Limit("Frontier support budget reached");}
        int atom(Atom a){Integer old=intern.get(a);if(old!=null)return old;if(defs.size()>=100000)throw new Limit("Factor DAG budget reached");int id=defs.size();defs.add(a);intern.put(a,id);return id;}
        void insert(SortedMap<Mono,Rational> p,Mono m,Rational c){Rational v=p.getOrDefault(m,Rational.ZERO).add(c);if(v.isZero())p.remove(m);else p.put(m,v);}
        SortedMap<Mono,Rational> flatten(Term t){
            SortedMap<Mono,Rational> out=new TreeMap<>();
            if(t.c.isZero())return out;
            if(t.m.powers.size()==1){Map.Entry<Integer,Integer>x=t.m.powers.entrySet().iterator().next();Atom a=defs.get(x.getKey());if(x.getValue()==1 && a.sum!=null)return scale(a.sum,t.c);}
            out.put(t.m,t.c);return out;
        }
        SortedMap<Mono,Rational> add(SortedMap<Mono,Rational>a,SortedMap<Mono,Rational>b){SortedMap<Mono,Rational>r=new TreeMap<>(a);for(Map.Entry<Mono,Rational>x:b.entrySet())insert(r,x.getKey(),x.getValue());return r;}
        SortedMap<Mono,Rational> scale(SortedMap<Mono,Rational>a,Rational c){SortedMap<Mono,Rational>r=new TreeMap<>();for(Map.Entry<Mono,Rational>x:a.entrySet())insert(r,x.getKey(),x.getValue().multiply(c));return r;}
        Term pack(SortedMap<Mono,Rational> p){if(p.isEmpty())return new Term(Rational.ZERO,UNIT);if(p.size()==1){Map.Entry<Mono,Rational>x=p.entrySet().iterator().next();return new Term(x.getValue(),x.getKey());}int id=atom(new Atom(null,p));return new Term(Rational.ONE,new Mono(Collections.singletonMap(id,1)));}
        SortedMap<Mono,Rational> delta(SortedMap<Mono,Rational> f,int id){
            SortedMap<Mono,Rational>d=new TreeMap<>();
            for(Map.Entry<Mono,Rational>x:f.entrySet())if(x.getKey().powers.containsKey(id)){
                insert(d,x.getKey(),x.getValue().negate());Mono base=x.getKey().removeOne(id);
                for(Map.Entry<Mono,Rational> child:defs.get(id).sum.entrySet()){
                    tick();insert(d,base.times(child.getKey()),x.getValue().multiply(child.getValue()));
                }
            }
            return d;
        }
        long probeWork;
        void guardProbe(Rational value){
            if(value.getNum().bitLength()+value.getDen().bitLength()>65536)throw new ProbeLimit();
        }
        Rational value(Term t,Map<Integer,Rational> memo){
            Rational v=t.c;
            guardProbe(v);
            for(Map.Entry<Integer,Integer>x:t.m.powers.entrySet()){
                if(++probeWork>Math.min(budget,10000))throw new ProbeLimit();
                Rational child=atomValue(x.getKey(),memo);
                if((long)(child.getNum().bitLength()+child.getDen().bitLength())*x.getValue()>65536)throw new ProbeLimit();
                v=v.multiply(child.pow(x.getValue()));guardProbe(v);
            }
            return v;
        }
        Rational atomValue(int id,Map<Integer,Rational> memo){
            Rational old=memo.get(id);if(old!=null)return old;Atom a=defs.get(id);Rational result=Rational.ONE;
            if(a.sum!=null){result=Rational.ZERO;for(Map.Entry<Mono,Rational>x:a.sum.entrySet()){result=result.add(value(new Term(x.getValue(),x.getKey()),memo));guardProbe(result);}}
            memo.put(id,result);return result;
        }
        Result witness(Term a,Term b,String left,String right){
            Map<Integer,Rational> memo=new HashMap<>();Rational av,bv;
            try{av=value(a,memo);bv=value(b,memo);}catch(ProbeLimit ex){return null;}
            if(av.equals(bv))return null;
            StringBuilder proof=new StringBuilder("{\"format\":\"symsat-witness-v1\",\"status\":\"NOT_EQUAL\",\"left\":").append(quote(left)).append(",\"right\":").append(quote(right)).append(",\"point\":{");
            boolean first=true;for(Atom x:defs)if(x.sum==null){if(!first)proof.append(',');first=false;proof.append(quote(x.name)).append(":\"1\"");}
            proof.append("},\"left_value\":").append(quote(av.toString())).append(",\"right_value\":").append(quote(bv.toString())).append('}');
            return new Result(Status.NOT_EQUAL,"Exact point witness",proof.toString(),work,0,0,defs.size());
        }
        // Reuse a previously defined sum inside a factor definition, by exact
        // inverse distribution. Every accepted contraction strictly shrinks that
        // definition and keeps the dependency DAG acyclic. Its trace is replayable.
        Mono quotient(Mono a,Mono b){
            Map<Integer,Integer> p=new TreeMap<>(a.powers);
            for(Map.Entry<Integer,Integer>x:b.powers.entrySet()){
                int degree=p.getOrDefault(x.getKey(),0)-x.getValue();
                if(degree<0)return null;if(degree==0)p.remove(x.getKey());else p.put(x.getKey(),degree);
            }
            return new Mono(p);
        }
        boolean dependsOn(int id,int target,Set<Integer> visited){
            if(id==target)return true;if(!visited.add(id))return false;Atom a=defs.get(id);
            if(a.sum!=null)for(Mono m:a.sum.keySet())for(int child:m.powers.keySet())if(dependsOn(child,target,visited))return true;
            return false;
        }
        void markLibrary(int id){
            if(!library.add(id))return;Atom a=originalDefs.get(id);
            if(a.sum!=null)for(Mono m:a.sum.keySet())for(int child:m.powers.keySet())markLibrary(child);
        }
        void contractDefinitions(SortedSet<Integer> eligible){
            Map<Integer,SortedSet<Integer>> index=new HashMap<>();
            for(int id:library){
                SortedMap<Mono,Rational> p=originalDefs.get(id).sum;
                if(p==null || p.size()<2)continue;Mono anchor=p.firstKey();int key=anchor.powers.isEmpty()?-1:anchor.powers.firstKey();
                index.computeIfAbsent(key,k->new TreeSet<>()).add(id);
            }
            Set<Integer> done=new HashSet<>();
            for(int target:eligible)contractDefinition(target,index,done);
        }
        void contractDefinition(int target,Map<Integer,SortedSet<Integer>> index,Set<Integer> done){
            if(!done.add(target))return;
            SortedMap<Mono,Rational> p=new TreeMap<>(defs.get(target).sum);
            boolean changed;
            do {
                changed=false;int bestUse=-1,bestSize=p.size();Mono bestBase=null;Rational bestC=null;SortedMap<Mono,Rational> best=null;
                SortedSet<Integer> candidates=new TreeSet<>(index.getOrDefault(-1,Collections.emptySortedSet()));
                for(Mono m:p.keySet())for(int variable:m.powers.keySet())candidates.addAll(index.getOrDefault(variable,Collections.emptySortedSet()));
                for(int use:candidates){
                    if(use==target)continue;
                    SortedMap<Mono,Rational> definition=originalDefs.get(use).sum;
                    if(definition.size()>p.size())continue;
                    Map.Entry<Mono,Rational> anchor=definition.entrySet().iterator().next();
                    for(Map.Entry<Mono,Rational> row:p.entrySet()){
                        Mono base=quotient(row.getKey(),anchor.getKey());if(base==null)continue;
                        Rational coefficient=row.getValue().divide(anchor.getValue());boolean complete=true;
                        for(Map.Entry<Mono,Rational> child:definition.entrySet()){
                            tick();Rational actual=p.get(base.times(child.getKey()));
                            if(actual==null || !actual.equals(coefficient.multiply(child.getValue()))){complete=false;break;}
                        }
                        if(!complete || dependsOn(use,target,new HashSet<>()))continue;
                        SortedMap<Mono,Rational> next=new TreeMap<>(p);
                        for(Map.Entry<Mono,Rational> child:definition.entrySet())insert(next,base.times(child.getKey()),coefficient.multiply(child.getValue()).negate());
                        insert(next,base.times(new Mono(Collections.singletonMap(use,1))),coefficient);
                        if(next.size()<bestSize){bestSize=next.size();best=next;bestUse=use;bestBase=base;bestC=coefficient;}
                    }
                }
                if(best!=null){
                    actions.add(new Action("contract",target,bestUse,bestBase,bestC));
                    p=best;defs.set(target,new Atom(null,p));changed=true;
                    contractDefinition(bestUse,index,done);
                }
                boolean inline;
                do {
                    inline=false;SortedSet<Integer> children=new TreeSet<>();
                    for(Mono m:p.keySet())children.addAll(m.powers.keySet());
                    for(int use:children){
                        Atom child=defs.get(use);
                        if(child.sum!=null && child.sum.size()<=1){
                            SortedMap<Mono,Rational> d=delta(p,use);
                            for(Map.Entry<Mono,Rational>x:d.entrySet())insert(p,x.getKey(),x.getValue());
                            actions.add(new Action("inline",target,use,null,null));
                            defs.set(target,new Atom(null,p));inline=true;break;
                        }
                    }
                }while(inline);
            }while(changed);
        }
        SortedMap<Mono,Rational> singleton(Mono m,Rational c){SortedMap<Mono,Rational>p=new TreeMap<>();p.put(m,c);return p;}
        int resultingSize(SortedMap<Mono,Rational> f,SortedMap<Mono,Rational>d){int n=f.size();for(Map.Entry<Mono,Rational>x:d.entrySet()){Rational old=f.get(x.getKey());Rational value=(old==null?Rational.ZERO:old).add(x.getValue());if(old==null && !value.isZero())n++;else if(old!=null && value.isZero())n--;}return n;}
        public Term visit(Const x){return new Term(x.val,UNIT);}
        public Term visit(Symbol x){if(x.isPatternVariable())throw new UnsupportedOperationException("Pattern variables excluded");int id=atom(new Atom(x.name,null));return new Term(Rational.ONE,new Mono(Collections.singletonMap(id,1)));}
        public Term visit(Plus x){return pack(add(flatten(x.a.accept(this)),flatten(x.b.accept(this))));}
        public Term visit(Minus x){return pack(add(flatten(x.a.accept(this)),scale(flatten(x.b.accept(this)),Rational.MINUS_ONE)));}
        public Term visit(UnaryMinus x){Term t=x.a.accept(this);return new Term(t.c.negate(),t.m);}
        public Term visit(Multiply x){Term a=x.a.accept(this),b=x.b.accept(this);return new Term(a.c.multiply(b.c),a.m.times(b.m));}
        Rational constant(Term t){if(!t.m.powers.isEmpty())throw new UnsupportedOperationException("Syntactically normalized constant required");return t.c;}
        public Term visit(Divide x){Term a=x.a.accept(this),b=x.b.accept(this);return new Term(a.c.divide(constant(b)),a.m);}
        public Term visit(Power x){Term a=x.base.accept(this),b=x.exp.accept(this);Rational exponent=constant(b);if(!exponent.isInteger())throw new UnsupportedOperationException("Integer powers required");int n=exponent.getNum().intValueExact();if(n<0)return new Term(constant(a).pow(n),UNIT);if(a.c.isZero())return new Term(a.c.pow(n),UNIT);Map<Integer,Integer>p=new TreeMap<>();for(Map.Entry<Integer,Integer>v:a.m.powers.entrySet()){long degree=(long)v.getValue()*n;if(degree>Integer.MAX_VALUE)throw new Limit("Exponent overflow");if(degree>0)p.put(v.getKey(),(int)degree);}return new Term(a.c.pow(n),new Mono(p));}
        public Term visit(FunctionCall x){throw new UnsupportedOperationException("Polynomial expressions only");}
        SortedMap<Mono,Rational> remap(SortedMap<Mono,Rational> p,Map<Integer,Integer> ids){
            SortedMap<Mono,Rational> out=new TreeMap<>();
            for(Map.Entry<Mono,Rational> row:p.entrySet()){
                Map<Integer,Integer> factors=new TreeMap<>();for(Map.Entry<Integer,Integer>x:row.getKey().powers.entrySet())factors.put(ids.get(x.getKey()),x.getValue());
                out.put(new Mono(factors),row.getValue());
            }
            return out;
        }
        Result result(Status s,String why,String left,String right,SortedMap<Mono,Rational> initial,SortedMap<Mono,Rational> end){
            StringBuilder b=new StringBuilder("{\"format\":"+quote((policy==Policy.REIFIED || policy==Policy.CERTIFIED)?"symsat-frontier-v2":"symsat-frontier-v1")+",\"left\":").append(quote(left)).append(",\"right\":").append(quote(right)).append(",\"status\":").append(quote(s.name())).append(",\"atoms\":[");
            boolean compact=policy==Policy.REIFIED || policy==Policy.CERTIFIED;
            Map<Integer,Integer> ids=new TreeMap<>();
            for(int i=0;i<originalDefs.size();i++)if(!compact || library.contains(i))ids.put(i,ids.size());
            boolean first=true;
            for(Map.Entry<Integer,Integer> entry:ids.entrySet()){
                if(!first)b.append(',');first=false;Atom a=originalDefs.get(entry.getKey());b.append("{\"id\":").append(entry.getValue());
                if(a.sum==null)b.append(",\"kind\":\"symbol\",\"name\":").append(quote(a.name));
                else b.append(",\"kind\":\"sum\",\"terms\":").append(ledger(remap(a.sum,ids)));
                b.append('}');
            }
            String steps=trace.toString();
            if(compact){
                List<String> mapped=new ArrayList<>();
                // Action IDs and term factors are remapped while actions are recorded below.
                for(Action action:actions)mapped.add(action.json(ids));
                steps="["+String.join(",",mapped)+"]";
            }
            b.append("],\"initial\":").append(ledger(remap(initial,ids))).append(",\"steps\":").append(steps).append(",\"final\":").append(ledger(remap(end,ids))).append('}');
            return new Result(s,why,b.toString(),work,trace.size(),peak,defs.size());
        }
    }
    private static String ledger(SortedMap<Mono,Rational> p){StringBuilder b=new StringBuilder("[");boolean first=true;for(Map.Entry<Mono,Rational>x:p.entrySet()){if(!first)b.append(',');first=false;b.append("{\"c\":").append(quote(x.getValue().toString())).append(",\"m\":[");boolean f=true;for(Map.Entry<Integer,Integer>v:x.getKey().powers.entrySet()){if(!f)b.append(',');f=false;b.append('[').append(v.getKey()).append(',').append(v.getValue()).append(']');}b.append("]}");}return b.append(']').toString();}
    private static String quote(String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray())switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
    private FrontierVerifier(){}
}
