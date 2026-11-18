import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class Main {
    static final int PORT=8080;
    static Connection db;

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        db=DriverManager.getConnection("jdbc:sqlite:exam_seating.db");
        try(Statement s=db.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS students(id INTEGER PRIMARY KEY AUTOINCREMENT, reg TEXT UNIQUE, name TEXT, dept TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS exams(id INTEGER PRIMARY KEY AUTOINCREMENT, subject TEXT, exam_date TEXT, exam_time TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS halls(id INTEGER PRIMARY KEY AUTOINCREMENT, hall_no TEXT UNIQUE, capacity INTEGER)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS allocations(id INTEGER PRIMARY KEY AUTOINCREMENT, exam_id INTEGER, student_id INTEGER, hall_id INTEGER, seat_no TEXT)");
        }
        HttpServer server=HttpServer.create(new InetSocketAddress(PORT),0);
        server.createContext("/api/", Main::api);
        server.createContext("/", Main::web);
        server.setExecutor(null); server.start();
        System.out.println("Exam Seating System running at http://localhost:"+PORT);
    }

    static void web(HttpExchange x) throws IOException {
        String path=x.getRequestURI().getPath();
        if(path.equals("/")) path="/index.html";
        Path file=Paths.get("web"+path).normalize();
        if(!file.startsWith(Paths.get("web")) || !Files.exists(file)){ send(x,404,"Not found","text/plain"); return; }
        String type=path.endsWith(".css")?"text/css":path.endsWith(".js")?"application/javascript":"text/html";
        send(x,200,Files.readString(file),type+"; charset=utf-8");
    }

    static void api(HttpExchange x) throws IOException {
        try {
            String path=x.getRequestURI().getPath(), method=x.getRequestMethod();
            if(path.equals("/api/students") && method.equals("GET")) {
                send(x,200,query("SELECT id,reg,name,dept FROM students ORDER BY id DESC"),"application/json"); return;
            }
            if(path.equals("/api/students") && method.equals("POST")) {
                Map<String,String> d=parse(body(x));
                try(PreparedStatement p=db.prepareStatement("INSERT INTO students(reg,name,dept) VALUES(?,?,?)")){
                    p.setString(1,d.get("reg"));p.setString(2,d.get("name"));p.setString(3,d.get("dept"));p.executeUpdate();
                }
                send(x,200,"{\"message\":\"Student added\"}","application/json");return;
            }
            if(path.equals("/api/halls") && method.equals("GET")) {
                send(x,200,query("SELECT id,hall_no,capacity FROM halls ORDER BY id DESC"),"application/json");return;
            }
            if(path.equals("/api/halls") && method.equals("POST")) {
                Map<String,String>d=parse(body(x));
                try(PreparedStatement p=db.prepareStatement("INSERT INTO halls(hall_no,capacity) VALUES(?,?)")){
                    p.setString(1,d.get("hall"));p.setInt(2,Integer.parseInt(d.get("capacity")));p.executeUpdate();
                }
                send(x,200,"{\"message\":\"Hall added\"}","application/json");return;
            }
            if(path.equals("/api/exams") && method.equals("GET")) {
                send(x,200,query("SELECT id,subject,exam_date,exam_time FROM exams ORDER BY id DESC"),"application/json");return;
            }
            if(path.equals("/api/exams") && method.equals("POST")) {
                Map<String,String>d=parse(body(x));
                try(PreparedStatement p=db.prepareStatement("INSERT INTO exams(subject,exam_date,exam_time) VALUES(?,?,?)")){
                    p.setString(1,d.get("subject"));p.setString(2,d.get("date"));p.setString(3,d.get("time"));p.executeUpdate();
                }
                send(x,200,"{\"message\":\"Exam added\"}","application/json");return;
            }
            if(path.equals("/api/allocate") && method.equals("POST")) {
                Map<String,String>d=parse(body(x)); int exam=Integer.parseInt(d.get("exam")), hall=Integer.parseInt(d.get("hall"));
                db.setAutoCommit(false);
                try {
                    try(PreparedStatement del=db.prepareStatement("DELETE FROM allocations WHERE exam_id=?")){del.setInt(1,exam);del.executeUpdate();}
                    int cap=0;try(PreparedStatement q=db.prepareStatement("SELECT capacity FROM halls WHERE id=?")){q.setInt(1,hall);ResultSet r=q.executeQuery();if(r.next())cap=r.getInt(1);}
                    List<Integer> ids=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id FROM students ORDER BY reg")){while(r.next())ids.add(r.getInt(1));}
                    if(ids.isEmpty()) throw new Exception("Add students first.");
                    if(cap<ids.size()) throw new Exception("Selected hall capacity is smaller than the student count.");
                    try(PreparedStatement p=db.prepareStatement("INSERT INTO allocations(exam_id,student_id,hall_id,seat_no) VALUES(?,?,?,?,?)".replace("?,?,?,?,?","?,?,?,?"))){
                        for(int i=0;i<ids.size();i++){p.setInt(1,exam);p.setInt(2,ids.get(i));p.setInt(3,hall);p.setString(4,"A"+(i+1));p.addBatch();}p.executeBatch();
                    }
                    db.commit();send(x,200,"{\"message\":\"Allocated "+ids.size()+" students\"}","application/json");
                } catch(Exception e){db.rollback();throw e;} finally{db.setAutoCommit(true);}
                return;
            }
            if(path.equals("/api/seating") && method.equals("GET")) {
                String id=x.getRequestURI().getQuery()==null?"0":x.getRequestURI().getQuery().replace("exam=","");
                send(x,200,query("SELECT s.reg,s.name,s.dept,h.hall_no,a.seat_no FROM allocations a JOIN students s ON s.id=a.student_id JOIN halls h ON h.id=a.hall_id WHERE a.exam_id="+Integer.parseInt(id)+" ORDER BY h.hall_no,a.seat_no"),"application/json");return;
            }
            send(x,404,"{\"error\":\"Not found\"}","application/json");
        } catch(Exception e){send(x,400,"{\"error\":\""+escape(e.getMessage())+"\"}","application/json");}
    }

    static String query(String sql)throws SQLException{
        StringBuilder b=new StringBuilder("[");boolean first=true;
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery(sql)){
            ResultSetMetaData m=r.getMetaData();int n=m.getColumnCount();
            while(r.next()){if(!first)b.append(",");first=false;b.append("{");
                for(int i=1;i<=n;i++){if(i>1)b.append(",");b.append("\"").append(m.getColumnLabel(i)).append("\":");
                    Object v=r.getObject(i);if(v==null)b.append("null");else if(v instanceof Number)b.append(v);else b.append("\"").append(escape(v.toString())).append("\"");}
                b.append("}");}
        }return b.append("]").toString();
    }
    static Map<String,String> parse(String json){
        Map<String,String> m=new HashMap<>();
        java.util.regex.Matcher a=java.util.regex.Pattern.compile("\"(.*?)\"\\s*:\\s*\"(.*?)\"").matcher(json);
        while(a.find())m.put(a.group(1),a.group(2));return m;
    }
    static String body(HttpExchange x)throws IOException{return new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);}
    static String escape(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ");}
    static void send(HttpExchange x,int code,String text,String type)throws IOException{
        byte[] b=text.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(code,b.length);
        try(OutputStream o=x.getResponseBody()){o.write(b);}
    }
}
