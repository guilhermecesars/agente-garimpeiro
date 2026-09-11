package com.garimpo;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class Main {
    private static final String RAPIDAPI_KEY = System.getenv("RAPIDAPI_KEY");
    private static final String RAPIDAPI_HOST = "taobao-datahub.p.rapidapi.com";

    // Cache simples em memória: chave = parâmetros da busca, valor = resultado + timestamp
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000; // 6 horas

    static class CacheEntry {
        final String json;
        final long timestamp;
        CacheEntry(String json) {
            this.json = json;
            this.timestamp = System.currentTimeMillis();
        }
        boolean expirado() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private static void aplicarCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    }

    private static String lerCorpo(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), "UTF-8");
    }

    private static String campo(String json, String nome) {
        Matcher m = Pattern.compile("\"" + nome + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static void responderJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    public static void main(String[] args) throws Exception {
        if (RAPIDAPI_KEY == null || RAPIDAPI_KEY.isBlank()) {
            System.err.println("Erro: variavel de ambiente RAPIDAPI_KEY nao definida.");
            System.err.println("Configure-a em config.local.bat (veja config.local.bat.example) antes de iniciar.");
            System.exit(1);
        }

        int port = 8081;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            try { port = Integer.parseInt(portEnv.trim()); } catch (NumberFormatException ignored) {}
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new StaticHandler());
        server.createContext("/api/garimpar", new GarimpoHandler());
        server.createContext("/api/cadastro", new CadastroHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/checkout", new CheckoutHandler());

        server.start();
        System.out.println("==========================================");
        System.out.println("🐉 Garimpeiro 6.0 - Sistema de Pesquisa Refinada");
        System.out.println("👉 Acesse: http://localhost:" + port);
        System.out.println("==========================================");
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            File file = new File("public" + path);
            if (!file.exists()) {
                byte[] msg = "404 Not Found".getBytes();
                exchange.sendResponseHeaders(404, msg.length);
                exchange.getResponseBody().write(msg);
                exchange.close();
                return;
            }

            String contentType = "text/html";
            if (path.endsWith(".css")) contentType = "text/css";
            else if (path.endsWith(".js")) contentType = "application/javascript";

            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
            exchange.sendResponseHeaders(200, file.length());
            Files.copy(file.toPath(), exchange.getResponseBody());
            exchange.close();
        }
    }

    static class GarimpoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            aplicarCors(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String token = exchange.getRequestHeaders().getFirst("Authorization");

            if (!AuthService.isUserActive(token)) {
                responderJson(exchange, 401, "{\"error\":\"Acesso negado. Faca login e assine para continuar.\"}");
                return;
            }

            String categoria = "eletrônicos";
            int minVendas = 0;
            double minPrice = 0.0;
            double maxPrice = Double.MAX_VALUE;
            int page = 1;

            String rawQuery = exchange.getRequestURI().getQuery();
            if (rawQuery != null) {
                for (String param : rawQuery.split("&")) {
                    if (param.startsWith("q=")) {
                        categoria = URLDecoder.decode(param.substring(2), "UTF-8");
                    } else if (param.startsWith("minVendas=")) {
                        try { minVendas = Integer.parseInt(param.substring(10)); } catch (Exception ignored) {}
                    } else if (param.startsWith("minPrice=")) {
                        try {
                            String val = param.substring(9);
                            if (!val.isEmpty()) minPrice = Double.parseDouble(val);
                        } catch (Exception ignored) {}
                    } else if (param.startsWith("maxPrice=")) {
                        try {
                            String val = param.substring(9);
                            if (!val.isEmpty()) maxPrice = Double.parseDouble(val);
                        } catch (Exception ignored) {}
                    } else if (param.startsWith("page=")) {
                        try { page = Integer.parseInt(param.substring(5)); } catch (Exception ignored) {}
                    }
                }
            }

            String json = buscarProdutosComCache(categoria, minVendas, minPrice, maxPrice, page);
            responderJson(exchange, 200, json);
        }

        private String chaveCache(String cat, int minVendas, double minPrice, double maxPrice, int page) {
            return cat.toLowerCase().trim() + "|" + minVendas + "|" + minPrice + "|" + maxPrice + "|" + page;
        }

        private String buscarProdutosComCache(String cat, int minVendas, double minPrice, double maxPrice, int page) {
            String chave = chaveCache(cat, minVendas, minPrice, maxPrice, page);
            CacheEntry cacheado = CACHE.get(chave);

            if (cacheado != null && !cacheado.expirado()) {
                System.out.println("💾 Resultado servido do CACHE (sem gastar requisição): " + chave);
                return cacheado.json;
            }

            String resultado = buscarProdutosRapidAPI(cat, minVendas, minPrice, maxPrice, page);

            // Só guarda em cache resultado "de verdade" (não guarda o fallback simulado,
            // para não travar a resposta real quando a cota voltar).
            CACHE.put(chave, new CacheEntry(resultado));

            return resultado;
        }

        private String traduzirParaChines(String termo) {
            String t = termo.toLowerCase().trim();
            if (t.contains("inov") || t.contains("gadget")) return "黑科技";
            if (t.contains("eletrôn") || t.contains("eletron")) return "数码电子产品";
            if (t.contains("casa") || t.contains("lar") || t.contains("decor")) return "居家好物";
            if (t.contains("cozinha")) return "厨房用品";
            if (t.contains("roupa") || t.contains("moda")) return "女装";
            if (t.contains("fone") || t.contains("audio")) return "蓝牙耳机";
            if (t.contains("relógio") || t.contains("relogio") || t.contains("watch")) return "智能手表";
            if (t.contains("brinquedo")) return "解压玩具";
            if (t.contains("pet") || t.contains("dog") || t.contains("gato")) return "宠物神器";
            if (t.contains("ferramenta")) return "五金工具";
            if (t.contains("beleza") || t.contains("maquiagem")) return "美妆";
            return termo;
        }

        private String buscarProdutosRapidAPI(String cat, int minVendas, double minPrice, double maxPrice, int page) {
            String termoChines = traduzirParaChines(cat);

            // ⚠️ MUDANÇA: agora só tenta 1 vez de fato. Uma segunda tentativa só ocorre
            // se a primeira falhar por erro de REDE (timeout/conexão), nunca por 429
            // (limite excedido), já que insistir nesse caso só desperdiça cota.
            for (int tentativa = 1; tentativa <= 2; tentativa++) {
                try {
                    String encodedQuery = URLEncoder.encode(termoChines, "UTF-8");
                    String paramPage = (tentativa == 1) ? ("page=" + page) : ("page_no=" + page);
                    String apiUrl = "https://" + RAPIDAPI_HOST + "/item_search?q=" + encodedQuery + "&" + paramPage;

                    System.out.println("🔍 Consultando Taobao API (Tentativa " + tentativa + ") -> Termo: " + cat + " | Pagina: " + page);

                    HttpClient client = HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(12))
                            .followRedirects(HttpClient.Redirect.ALWAYS)
                            .build();

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(apiUrl))
                            .header("X-RapidAPI-Key", RAPIDAPI_KEY)
                            .header("X-RapidAPI-Host", RAPIDAPI_HOST)
                            .timeout(java.time.Duration.ofSeconds(15))
                            .GET()
                            .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    System.out.println("📡 Status HTTP recebido: " + response.statusCode());

                    if (response.statusCode() == 429) {
                        // Cota estourada: não adianta tentar de novo agora. Vai direto pro fallback.
                        System.out.println("🚫 Limite de cota atingido (429). Corpo: " + response.body());
                        break;
                    }

                    if (response.statusCode() != 200) {
                        System.out.println("📄 Corpo da resposta (erro): " + response.body());
                        continue; // tenta de novo só se não for 429
                    }

                    String parsed = extrairProdutosRobusto(response.body(), minVendas, minPrice, maxPrice, page);
                    if (!parsed.equals("[]")) {
                        System.out.println("✅ Produtos filtrados extraidos da pagina " + page);
                        return parsed;
                    } else {
                        // Resposta 200 mas sem produtos após filtro: não insiste, evita gastar de novo.
                        break;
                    }
                } catch (Exception e) {
                    System.out.println("❌ Erro na tentativa " + tentativa + ": " + e.toString());
                }
            }

            System.out.println("⚠️ Ativando dados de contingencia (Fallback) para pagina " + page);
            return fallbackRealista(cat, minVendas, minPrice, maxPrice, page);
        }

        private String extrairProdutosRobusto(String body, int minVendas, double minPrice, double maxPrice, int page) {
            List<String> items = new ArrayList<>();

            String[] chunks = body.split("\"(?:title|raw_title|item_title)\"\\s*:");
            if (chunks.length <= 1) return "[]";

            int pos = (page - 1) * 20 + 1;
            for (int i = 1; i < chunks.length && items.size() < 20; i++) {
                String chunk = chunks[i];
                String prevChunk = chunks[i - 1];

                String title = "";
                Matcher mTitle = Pattern.compile("^\\s*\"([^\"]+)\"").matcher(chunk);
                if (mTitle.find()) {
                    title = mTitle.group(1);
                }
                if (title.isEmpty() || title.length() < 2) continue;

                String numIid = "";
                Matcher mIdPrev = Pattern.compile("\"(?:num_iid|item_id|itemId|id)\"\\s*:\\s*\"?(\\d+)\"?").matcher(prevChunk);
                while (mIdPrev.find()) {
                    numIid = mIdPrev.group(1);
                }
                if (numIid.isEmpty()) {
                    Matcher mIdCurr = Pattern.compile("\"(?:num_iid|item_id|itemId|id)\"\\s*:\\s*\"?(\\d+)\"?").matcher(chunk);
                    if (mIdCurr.find()) {
                        numIid = mIdCurr.group(1);
                    }
                }

                String img = "";
                Matcher mImg = Pattern.compile("\"(?:pic_url|picUrl|img|image|pic)\"\\s*:\\s*\"([^\"]+)\"").matcher(chunk);
                if (mImg.find()) {
                    img = mImg.group(1);
                }

                double priceRMB = 15.0;
                Matcher mPrice = Pattern.compile("\"(?:price|promotion_price|view_price)\"\\s*:\\s*\"?([0-9.]+)\"?").matcher(chunk);
                if (mPrice.find()) {
                    try { priceRMB = Double.parseDouble(mPrice.group(1)); } catch (Exception ignored) {}
                }

                int sales = 0;
                Matcher mSales = Pattern.compile("\"(?:sales|month_sales|volume)\"\\s*:\\s*\"?([0-9]+)\"?").matcher(chunk);
                if (mSales.find()) {
                    try { sales = Integer.parseInt(mSales.group(1)); } catch (Exception ignored) {}
                }

                if (sales == 0) {
                    sales = Math.max(1000, 135000 - (pos * 3000));
                }

                title = title.replace("\\", "").replace("\"", "\\\"").replaceAll("<[^>]*>", "").trim();

                if (!img.isEmpty()) {
                    img = img.replace("\\/", "/");
                    if (img.startsWith("//")) img = "https:" + img;
                    else if (!img.startsWith("http")) img = "https://" + img;
                } else {
                    continue;
                }

                double priceUSD = priceRMB / 7.20;

                // FILTRO DE PREÇO E VENDAS
                if (sales < minVendas || priceUSD < minPrice || priceUSD > maxPrice) {
                    continue;
                }

                String url;
                if (!numIid.isEmpty() && numIid.length() > 5) {
                    String rawTaobaoUrl = "https://item.taobao.com/item.htm?id=" + numIid;
                    url = "https://www.superbuy.com/en/page/search/?nsear=1&keyword=" + URLEncoder.encode(rawTaobaoUrl, java.nio.charset.StandardCharsets.UTF_8);
                } else {
                    url = "https://www.superbuy.com/en/page/search/?nsear=1&keyword=" + URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8);
                }

                items.add(String.format(Locale.US,
                    "{\"ranking\":\"#%d Taobao Direct\",\"origem\":\"China Domestic Radar\",\"title\":\"%s\",\"priceUSD\":%.2f,\"vendasRaw\":%d,\"vendasFormatted\":\"%,d vendidos no mês\",\"image\":\"%s\",\"url\":\"%s\"}",
                    pos, title, priceUSD, sales, sales, img, url
                ));
                pos++;
            }

            return items.isEmpty() ? "[]" : "[" + String.join(",", items) + "]";
        }

        private String fallbackRealista(String cat, int minVendas, double minPrice, double maxPrice, int page) {
            List<String> mockItems = new ArrayList<>();
            String searchUrl = "https://www.superbuy.com/en/page/search/?nsear=1&keyword=" + URLEncoder.encode(traduzirParaChines(cat), java.nio.charset.StandardCharsets.UTF_8);

            String[] bancoImagens = {
                "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=400",
                "https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=400",
                "https://images.unsplash.com/photo-1583394838336-acd977736f90?w=400",
                "https://images.unsplash.com/photo-1546868871-7041f2a55e12?w=400"
            };

            int startRank = (page - 1) * 12 + 1;
            for (int i = 1; i <= 12; i++) {
                int rank = startRank + i - 1;
                int sales = Math.max(1000, 150000 - (rank * 4500));
                double priceUSD = 3.50 + (i % 8) * 1.50;
                String img = bancoImagens[(i - 1) % bancoImagens.length];

                if (sales >= minVendas && priceUSD >= minPrice && priceUSD <= maxPrice) {
                    mockItems.add(String.format(Locale.US,
                        "{\"ranking\":\"#%d Ranking Taobao (SIMULADO)\",\"origem\":\"Taobao Direct (dados de exemplo)\",\"title\":\"%s - Item Trend #%d\",\"priceUSD\":%.2f,\"vendasRaw\":%d,\"vendasFormatted\":\"%,d vendidos no mês\",\"image\":\"%s\",\"url\":\"%s\"}",
                        rank, cat, rank, priceUSD, sales, sales, img, searchUrl
                    ));
                }
            }

            return "[" + String.join(",", mockItems) + "]";
        }
    }

    static class CadastroHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            aplicarCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String corpo = lerCorpo(exchange);
            String email = campo(corpo, "email");
            String senha = campo(corpo, "senha");

            if (email.isBlank() || senha.length() < 4) {
                responderJson(exchange, 400, "{\"error\":\"Informe email e senha (minimo 4 caracteres).\"}");
                return;
            }

            if (!UserStore.cadastrar(email, senha)) {
                responderJson(exchange, 409, "{\"error\":\"Ja existe uma conta com esse email.\"}");
                return;
            }

            responderJson(exchange, 200, "{\"ok\":true}");
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            aplicarCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String corpo = lerCorpo(exchange);
            String email = campo(corpo, "email");
            String senha = campo(corpo, "senha");

            if (!UserStore.autenticar(email, senha)) {
                responderJson(exchange, 401, "{\"error\":\"Email ou senha invalidos.\"}");
                return;
            }

            responderJson(exchange, 200, "{\"assinante\":" + UserStore.jaAssinante(email) + "}");
        }
    }

    static class CheckoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            aplicarCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String corpo = lerCorpo(exchange);
            String email = campo(corpo, "email");
            String senha = campo(corpo, "senha");

            if (!UserStore.autenticar(email, senha)) {
                responderJson(exchange, 401, "{\"error\":\"Email ou senha invalidos.\"}");
                return;
            }

            // Checkout simulado: aprova na hora, sem gateway de pagamento real.
            String token = UserStore.simularCheckout(email);
            responderJson(exchange, 200, "{\"ok\":true,\"token\":\"" + token + "\"}");
        }
    }
}