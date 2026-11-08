import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// The "controller" layer: translates HTTP requests into service calls and results into JSON.
// Uses the web server that ships inside the JDK (com.sun.net.httpserver), so no libraries needed.
public class subsplitServer {

    static final Store store = new Store();
    static final accountService accounts = new accountService(store);
    static final balanceService service = new balanceService(store);

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/", subsplitServer::handleApi);
        server.createContext("/", subsplitServer::handlePage);
        server.start();
        System.out.println("Subsplit running at http://localhost:8080");
    }

    // Serves the UI (index.html, which must sit in the folder you run the program from)
    static void handlePage(HttpExchange ex) throws IOException {
        byte[] html = Files.readAllBytes(Path.of("index.html"));
        send(ex, 200, "text/html; charset=utf-8", html);
    }

    static void handleApi(HttpExchange ex) throws IOException {
        int status = 200;
        String body;
        try {
            String method = ex.getRequestMethod();
            // "/api/groups/1/balances" -> ["", "api", "groups", "1", "balances"]
            String[] parts = ex.getRequestURI().getPath().split("/");
            Map<String, String> form = method.equals("POST") ? readForm(ex) : Map.of();
            body = route(ex, method, parts, form);
        } catch (NotFound e) {
            status = 404;
            body = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (accessError e) {      // 401 not logged in, 403 not allowed
            status = e.status;
            body = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (RuntimeException e) { // bad numbers, missing fields, validation errors
            status = 400;
            body = "{\"error\":" + q(String.valueOf(e.getMessage())) + "}";
        }
        send(ex, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static String route(HttpExchange ex, String method, String[] p, Map<String, String> f) {
        String resource = p.length > 2 ? p[2] : "";
        boolean post = method.equals("POST");

        // ----- accounts (no login needed) -----
        if (resource.equals("signup") && post) {
            User u = accounts.signUp(need(f, "name"), need(f, "email"), needRaw(f, "password"));
            startSession(ex, u);
            return userJson(u, true);
        }
        if (resource.equals("login") && post) {
            User u = accounts.login(need(f, "email"), needRaw(f, "password"));
            startSession(ex, u);
            return userJson(u, true);
        }
        if (resource.equals("logout") && post) {
            String token = cookie(ex, "session");
            if (token != null) store.endSession(token);
            ex.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
            return "{\"ok\":true}";
        }

        // ----- everything below needs a logged-in user -----
        User me = currentUser(ex);

        if (resource.equals("me")) return userJson(me, true);

        if (resource.equals("users")) {
            return "[" + service.peopleFor(me).stream().map(u -> userJson(u, false)).collect(Collectors.joining(",")) + "]";
        }

        if (resource.equals("groups")) {
            if (p.length == 3) {
                if (post) {
                    Group g = service.createGroup(me, need(f, "serviceName"),
                            new BigDecimal(need(f, "totalCost")),
                            LocalDate.parse(need(f, "startDate")),
                            Group.Split.valueOf(f.getOrDefault("split", "EVEN")));
                    return groupJson(g);
                }
                return "[" + service.groupsFor(me).stream().map(subsplitServer::groupJson).collect(Collectors.joining(",")) + "]";
            }
            long groupId = Long.parseLong(p[3]);
            String action = p.length > 4 ? p[4] : "";

            if (action.equals("members") && post) {
                BigDecimal share = f.getOrDefault("share", "").isBlank() ? null : new BigDecimal(f.get("share").trim());
                service.addMember(me, groupId, need(f, "who"), share);
                return "{\"ok\":true}";
            }
            if (action.equals("payments") && post) {
                Long who = f.getOrDefault("userId", "").isBlank() ? null : Long.valueOf(f.get("userId").trim());
                service.recordPayment(me, groupId, who, new BigDecimal(need(f, "amount")));
                return "{\"ok\":true}";
            }
            if (action.equals("cancel") && post) {
                return groupJson(service.cancel(me, groupId));
            }
            if (action.equals("balances")) {
                return balancesJson(service.balances(me, groupId));
            }
        }
        throw new NotFound("No such endpoint");
    }

    // ---------- login sessions (a random token kept in a cookie) ----------

    static void startSession(HttpExchange ex, User u) {
        String token = Auth.newToken();
        store.putSession(token, u.id());
        ex.getResponseHeaders().add("Set-Cookie", "session=" + token + "; Path=/; HttpOnly; SameSite=Lax");
    }

    static User currentUser(HttpExchange ex) {
        return store.userForSession(cookie(ex, "session"))
                .orElseThrow(() -> new accessError(401, "Please log in"));
    }

    static String cookie(HttpExchange ex, String name) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    // ---------- JSON by hand (fine for flat data like this) ----------

    // email is only included for the logged-in person's own record
    static String userJson(User u, boolean withEmail) {
        return "{\"id\":" + u.id() + ",\"name\":" + q(u.name())
                + (withEmail ? ",\"email\":" + q(u.email()) : "") + "}";
    }

    static String groupJson(Group g) {
        LocalDate today = LocalDate.now();
        return "{\"id\":" + g.id() + ",\"ownerId\":" + g.ownerId() + ",\"serviceName\":" + q(g.serviceName())
                + ",\"totalCost\":" + g.totalCost() + ",\"split\":" + q(g.split().name())
                + ",\"startDate\":" + qn(g.startDate()) + ",\"cycleStart\":" + qn(g.cycleStart(today))
                + ",\"nextRenewal\":" + qn(g.nextRenewal(today)) + ",\"endsOn\":" + qn(g.endsOn())
                + ",\"ended\":" + g.ended(today) + "}";
    }

    static String balancesJson(List<balanceService.MemberBalance> list) {
        return "[" + list.stream().map(b -> "{\"userId\":" + b.userId() + ",\"userName\":" + q(b.userName())
                        + ",\"share\":" + b.share() + ",\"paid\":" + b.paid()
                        + ",\"owed\":" + b.owed() + ",\"status\":" + q(b.status()) + "}")
                .collect(Collectors.joining(",")) + "]";
    }

    // wraps text in quotes and escapes the characters JSON doesn't allow raw
    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // a date as a JSON string, or null when there isn't one
    static String qn(LocalDate d) { return d == null ? "null" : q(d.toString()); }

    // ---------- helpers ----------

    // Reads a form body like "name=Minna&email=a%40b.com" into a map
    static Map<String, String> readForm(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<>();
        if (raw.isBlank()) return map;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(key, value);
        }
        return map;
    }

    static String need(Map<String, String> f, String key) {
        String v = f.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing field: " + key);
        return v.trim();
    }

    // like need(), but doesn't trim (spaces can be part of a password)
    static String needRaw(Map<String, String> f, String key) {
        String v = f.get(key);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("Missing field: " + key);
        return v;
    }

    static void send(HttpExchange ex, int status, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
