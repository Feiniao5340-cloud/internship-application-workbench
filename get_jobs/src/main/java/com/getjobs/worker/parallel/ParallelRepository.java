package com.getjobs.worker.parallel;

import org.json.*;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** One transactional writer. No worker ever edits another worker's JSON file. */
@Component
public class ParallelRepository {
    private final String url;
    private volatile java.util.function.Consumer<JSONObject> notification=e->{};
    public void onEvent(java.util.function.Consumer<JSONObject> consumer){notification=Objects.requireNonNull(consumer);}
    public ParallelRepository() {this(Path.of("data","parallel","workbench.db"));}
    public ParallelRepository(Path database) {
        try {
            Files.createDirectories(database.toAbsolutePath().getParent());url="jdbc:sqlite:"+database.toAbsolutePath();
            try(Connection c=open();Statement s=c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("CREATE TABLE IF NOT EXISTS documents (platform TEXT NOT NULL,name TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(platform,name))");
                s.execute("CREATE TABLE IF NOT EXISTS rounds (id TEXT PRIMARY KEY,platform TEXT NOT NULL,started_at TEXT NOT NULL,body TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS candidates (round_id TEXT NOT NULL,platform TEXT NOT NULL,job_id TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(round_id,platform,job_id))");
                s.execute("CREATE TABLE IF NOT EXISTS claims (platform TEXT NOT NULL,account TEXT NOT NULL,job_id TEXT NOT NULL,day TEXT NOT NULL,state TEXT NOT NULL,at TEXT NOT NULL,PRIMARY KEY(platform,account,job_id,day))");
                s.execute("CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY,platform TEXT NOT NULL,round_id TEXT NOT NULL,type TEXT NOT NULL,message TEXT NOT NULL,at TEXT NOT NULL)");
            }
        }catch(Exception e){throw new IllegalStateException("并行数据库初始化失败",e);}
    }
    private Connection open() throws SQLException {Connection c=DriverManager.getConnection(url);try(Statement s=c.createStatement()){s.execute("PRAGMA busy_timeout=5000");}return c;}
    public synchronized JSONObject document(String platform,String name) {
        try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT body FROM documents WHERE platform=? AND name=?")) {
            p.setString(1,platform);p.setString(2,name);try(ResultSet r=p.executeQuery()){return r.next()?new JSONObject(r.getString(1)):new JSONObject();}
        }catch(SQLException e){throw new IllegalStateException("并行状态读取失败",e);}
    }
    public synchronized void document(String platform,String name,JSONObject body) {
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement("INSERT INTO documents(platform,name,body) VALUES(?,?,?) ON CONFLICT(platform,name) DO UPDATE SET body=excluded.body")) {
                    p.setString(1,platform);p.setString(2,name);p.setString(3,body.toString());p.executeUpdate();
                }
                if(name.equals("round.json")&&body.has("id")) {
                    JSONObject round=new JSONObject(body.toString()).put("platform",platform);
                    try(PreparedStatement p=c.prepareStatement("INSERT INTO rounds(id,platform,started_at,body) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body")) {
                        p.setString(1,round.getString("id"));p.setString(2,platform);p.setString(3,round.optString("startedAt"));p.setString(4,round.toString());p.executeUpdate();
                    }
                }
                if(name.equals("records.json"))try(PreparedStatement p=c.prepareStatement("INSERT INTO candidates(round_id,platform,job_id,body) VALUES(?,?,?,?) ON CONFLICT(round_id,platform,job_id) DO UPDATE SET body=excluded.body")) {
                    for(String id:body.keySet()) {JSONObject entry=body.getJSONObject(id);if(entry.optString("roundId").isBlank())continue;
                        p.setString(1,entry.getString("roundId"));p.setString(2,platform);p.setString(3,id);p.setString(4,entry.toString());p.addBatch();}
                    p.executeBatch();
                }
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }catch(Exception e){throw new IllegalStateException("并行状态保存失败，停止发送",e);}
    }
    public synchronized JSONArray rounds(String platform) {
        JSONArray result=new JSONArray();String query="SELECT body FROM rounds"+(platform==null?"":" WHERE platform=?")+" ORDER BY started_at DESC LIMIT 200";
        try(Connection c=open();PreparedStatement p=c.prepareStatement(query)){if(platform!=null)p.setString(1,platform);try(ResultSet r=p.executeQuery()){while(r.next())result.put(new JSONObject(r.getString(1)));}}
        catch(SQLException e){throw new IllegalStateException("轮次读取失败",e);}return result;
    }
    public synchronized JSONObject candidates(String platform,String round) {
        JSONObject result=new JSONObject();String q="SELECT job_id,body FROM candidates WHERE platform=? AND round_id=?";
        try(Connection c=open();PreparedStatement p=c.prepareStatement(q)){p.setString(1,platform);p.setString(2,round);try(ResultSet r=p.executeQuery()){while(r.next())result.put(r.getString(1),new JSONObject(r.getString(2)));}}
        catch(SQLException e){throw new IllegalStateException("候选快照读取失败",e);}return result;
    }
    public synchronized boolean claim(String platform,String account,String job,String day) {
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM claims WHERE platform=? AND account=? AND job_id=? AND (day=? OR state IN ('in_progress','uncertain')) LIMIT 1")) {
                    p.setString(1,platform);p.setString(2,account);p.setString(3,job);p.setString(4,day);
                    try(ResultSet r=p.executeQuery()){if(r.next()){c.rollback();return false;}}
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO claims VALUES(?,?,?,?,?,?)")){
                    p.setString(1,platform);p.setString(2,account);p.setString(3,job);p.setString(4,day);p.setString(5,"in_progress");p.setString(6,Instant.now().toString());p.executeUpdate();}
                c.commit();return true;
            }catch(Exception e){c.rollback();throw e;}
        }catch(Exception e){throw new IllegalStateException("投递占位失败，未发送",e);}
    }
    public synchronized void finishClaim(String platform,String account,String job,String day,String status) {
        String state=status.equals("complete")?"confirmed":status.equals("screened")?"unsent":"uncertain";
        String query=state.equals("unsent")?"DELETE FROM claims WHERE platform=? AND account=? AND job_id=? AND day=? AND state='in_progress'":"UPDATE claims SET state=?,at=? WHERE platform=? AND account=? AND job_id=? AND day=?";
        try(Connection c=open();PreparedStatement p=c.prepareStatement(query)) {
            int i=1;if(!state.equals("unsent")){p.setString(i++,state);p.setString(i++,Instant.now().toString());}
            p.setString(i++,platform);p.setString(i++,account);p.setString(i++,job);p.setString(i,day);p.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException("投递结果保存失败，请核对后处理",e);}
    }
    public synchronized void resolveUnsent(String platform,String job) {
        try(Connection c=open();PreparedStatement p=c.prepareStatement("DELETE FROM claims WHERE platform=? AND job_id=? AND state IN ('in_progress','uncertain')")) {p.setString(1,platform);p.setString(2,job);p.executeUpdate();}
        catch(SQLException e){throw new IllegalStateException(e);}
    }
    public synchronized void resolveConfirmed(String platform,String job) {
        try(Connection c=open();PreparedStatement p=c.prepareStatement("UPDATE claims SET state='confirmed',at=? WHERE platform=? AND job_id=? AND state IN ('in_progress','uncertain')")) {
            p.setString(1,Instant.now().toString());p.setString(2,platform);p.setString(3,job);p.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException("人工核对结果无法写入投递占位，仍保持锁定",e);}
    }
    public synchronized void event(String platform,String round,String type,String message) {
        String id=UUID.randomUUID().toString(),at=Instant.now().toString();
        try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?,?)")) {
            p.setString(1,id);p.setString(2,platform);p.setString(3,round);p.setString(4,type);p.setString(5,message);p.setString(6,at);p.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException("事件保存失败",e);}
        if(Set.of("round_failed","round_expired","daily_limit","manual_delivery_failed").contains(type)) {
            JSONObject event=new JSONObject().put("id",id).put("type",type).put("round",round).put("platform",platform)
                    .put("message",message).put("time",at).put("paused",!type.equals("round_expired"));
            try{notification.accept(event);}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("开发版桌面通知失败，事件已保存：{}",id,e);}
        }
    }
    public synchronized JSONArray events() {
        JSONArray events=new JSONArray();try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT * FROM events ORDER BY at DESC LIMIT 200");ResultSet r=p.executeQuery()) {
            while(r.next())events.put(new JSONObject().put("id",r.getString("id")).put("platform",r.getString("platform")).put("round",r.getString("round_id")).put("type",r.getString("type")).put("message",r.getString("message")).put("at",r.getString("at")));
        }catch(SQLException e){throw new IllegalStateException(e);}return events;
    }
}
