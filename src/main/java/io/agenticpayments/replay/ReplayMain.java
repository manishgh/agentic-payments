package io.agenticpayments.replay;

import org.postgresql.ds.PGSimpleDataSource;
import java.nio.file.*;

/** Private local CLI, deliberately absent from the HTTP routing table. */
public final class ReplayMain {
    private ReplayMain(){}
    public static void main(String[] args){
        try{
            if(args.length==1&&args[0].equals("--example")){System.out.println(ReplayExamples.twoIntents().canonicalBytes());return;}
            ReplayPlan plan;
            if(args.length==1&&args[0].equals("--demo"))plan=ReplayExamples.twoIntents();
            else if(args.length==2&&args[0].equals("--plan")){Path file=Path.of(args[1]);if(Files.size(file)>4*1024*1024)throw new IllegalArgumentException("Plan is too large");plan=ReplayPlanJson.parse(Files.readString(file));}
            else throw new IllegalArgumentException("Expected --example, --demo or --plan path");
            String url=System.getenv("PAYMENTS_DB_URL"),user=System.getenv("PAYMENTS_DB_USER"),password=System.getenv("PAYMENTS_DB_PASSWORD");if(url==null||user==null||password==null)throw new IllegalArgumentException("Local database environment is required");
            var ds=new PGSimpleDataSource();ds.setURL(url);ds.setUser(user);ds.setPassword(password);ds.setLoginTimeout(10);ds.setConnectTimeout(10);ds.setSocketTimeout(20);
            ReplayReport report=new ReplayRunner(ds).execute(plan,System.getenv().getOrDefault("PAYMENTS_REPLAY_TENANT","replay-cli"));System.out.println(report.json());if(!report.outcome().equals("PASS"))System.exit(1);
        }catch(IllegalArgumentException e){System.err.println("{\"error\":\"INVALID_REPLAY_INPUT\"}");System.exit(2);}
        catch(Exception e){System.err.println("{\"error\":\"REPLAY_EXECUTION_FAILED\"}");System.exit(2);}
    }
}
