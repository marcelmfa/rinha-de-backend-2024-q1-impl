package caravanacloud.cached;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.MapMaker;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
// curl -v -X GET http://localhost:9999/clientes/1/extrato

// curl -v -X POST -H "Content-Type: application/json" -d '{"valor": 100,
// "tipo": "c", "descricao": "Deposito"}'
// http:///localhost:9999/clientes/1/transacoes

//@WebServlet(value = "/cached/*")
public class RinhaServlet extends HttpServlet {
    private static final String WARMUP_QUERY = "CREATE EXTENSION IF NOT EXISTS pg_prewarm; SELECT pg_prewarm('transacoes');";
    private static final String EXTRATO_QUERY = "SELECT json_agg(t) FROM (SELECT * FROM transacoes WHERE id = ?) t";
    private static final String TRANSACAO_QUERY = "INSERT INTO transacoes (cliente_id, valor, tipo, descricao) VALUES (?, ?, ?, ?)";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    public static Map<Integer, Cliente> cache;
    public static final int shard = envInt("RINHA_SHARD", 1);

    @ConfigProperty(name = "quarkus.profile")
    String profile;

    static {
        cache = Map.of(
                1, Cliente.of(1, 1, "o barato sai caro", 0, 1000 * 100),
                2, Cliente.of(0, 2, "zan corp ltda", 0, 800 * 100),
                3, Cliente.of(1, 3, "les cruders", 0, 10000 * 100),
                4, Cliente.of(0, 4, "padaria joia de cocaia", 0, 100000 * 100),
                5, Cliente.of(1, 5, "kid mais", 0, 5000 * 100));
    }

    @Inject
    DataSource ds;

    private static Integer envInt(String varname, int defaultVal) {
        var env = System.getenv(varname);
        if (env == null)
            return defaultVal;
        return Integer.parseInt(env);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Log.info("CACHED GET");
        var pathInfo = req.getPathInfo();
        // var id = pathInfo.substring(10,11);
        var id = pathInfo.split("/")[2];
        // Log.info(pathInfo + " => " + id);
        processExtrato(Integer.valueOf(id), resp);
    }

    private void processExtrato(Integer id, HttpServletResponse resp) throws IOException {
        if ("dev".equals(profile) || shard == shardOf(id)) {
            Log.infof("Extrato from cache [%s]", id);
            var cliente = cache.get(id);
            write(cliente, resp);
            return;
        } else {
            Log.infof("Extrato from other shard profile[%s] [%s,%s, %s]",profile, id, shard, shardOf(id));
            importExtrato(shard, id, resp);
        }
    }

    private void write(Cliente cliente, HttpServletResponse resp) {
        try {
            var body = Map.of("limite", cliente.limite, "saldo", cliente.saldo);
            if (resp == null)
                return;
            resp.setHeader("x-rinha-cache", "hit");
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            var output = objectMapper.writeValueAsString(body);
            resp.getWriter().write(output);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void importExtrato(Integer shard, Integer id, HttpServletResponse resp) {
        int port;
        if ("dev".equals(profile)) {
            port = 9999;
        } else {
            port = 9000 + (shard + 1);
        }
        String urlString = "http://127.0.0.1:" + port + "/clientes/" + id + "/extrato";
        try {
            URL url = URI.create(urlString).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Set the request method, if necessary. Default is GET.
            connection.setRequestMethod("GET");

            // Set a User-Agent or any header if you need
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            // Get the input stream of the connection
            try (InputStream inputStream = connection.getInputStream();
                    ServletOutputStream outputStream = resp.getOutputStream()) {

                // Set the content type of the response, if known. Could be "text/html", etc.
                resp.setContentType("application/json");
                resp.setHeader("x-rinha-cache", "miss");

                byte[] buffer = new byte[4096];
                int bytesRead;

                // Read from the URL and write to the servlet response
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        } catch (IOException e) {
            Log.info("Failed to import extrato from " + urlString);
        }
        return;
    }

    private int shardOf(Integer id) {
        return id % 2;
    }

    private void sendError(HttpServletResponse resp, int sc, String msg) throws IOException {
        if (sc == 500)
            Log.warn(msg);
        if (resp != null)
            resp.sendError(sc, msg);
        else
            Log.warnf("[%s] %s", sc, msg);
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Log.info("CACHED POST");
        var pathInfo = req.getPathInfo();
        // var id = pathInfo.substring(10,11);
        var id = pathInfo.split("/")[2];
        // Log.info(pathInfo + " => " + id);
        JsonNode json;
        try (BufferedReader reader = req.getReader()) {
            json = objectMapper.readTree(reader);
        } catch (Exception e) {
            sendError(resp, SC_BAD_REQUEST, "Invalid request body " + e.getMessage());
            return;
        }
        postTransacao(Integer.valueOf(id), json, resp);
        return;
    }

    private void postTransacao(Integer id, JsonNode t, HttpServletResponse resp) throws IOException {
        // Validate and process the transaction as in the original resource
        var valorNumber = t.get("valor").asText();
        if (valorNumber == null || valorNumber.contains(".")) {
            if (resp != null)
                sendError(resp, 422, "Valor invalido");
            return;
        }

        Integer valor = null;
        try {
            valor = Integer.parseInt((String) valorNumber);
        } catch (NumberFormatException e) {
            if (resp != null)
                sendError(resp, 422, "Valor invalido");
            return;
        }

        var tipo = (String) t.get("tipo").asText();
        if (tipo == null || !("c".equals(tipo) || "d".equals(tipo))) {
            if (resp != null)
                sendError(resp, 422, "Tipo invalido");
            return;
        }

        var descricao = (String) t.get("descricao").asText();
        if (descricao == null || descricao.isEmpty() || descricao.length() > 10 || "null".equals(descricao)) {
            if (resp != null)
                sendError(resp, 422, "Descricao invalida");
            return;
        }

        try (var conn = ds.getConnection();
                var stmt = conn.prepareStatement(TRANSACAO_QUERY)) {
            stmt.setInt(1, id);
            stmt.setInt(2, valor);
            stmt.setString(3, tipo);
            stmt.setString(4, descricao);
            stmt.executeUpdate();
            //TODO: Update cache
            if (resp != null) {
                resp.setStatus(201);
                resp.setHeader("Location", "/clientes/" + id + "/transacoes");
            }
        } catch (SQLException e) {
            handleSQLException(e, resp);
        } catch (Exception e) {
            sendError(resp, SC_INTERNAL_SERVER_ERROR, "Erro ao processar a transacao " + e.getMessage());
        }
    }

    private void handleSQLException(SQLException e, HttpServletResponse resp) throws IOException {
        var msg = e.getMessage();
        if (msg.contains("LIMITE_INDISPONIVEL")) {
            sendError(resp, 422, "Erro: Limite indisponivel");
        } else if (msg.contains("fk_clientes_transacoes_id")) {
            sendError(resp, SC_NOT_FOUND, "Erro: Cliente inexistente");
        } else {
            sendError(resp, SC_INTERNAL_SERVER_ERROR, "Erro SQL ao manipular a transacao: " + e.getMessage());
        }
    }
}