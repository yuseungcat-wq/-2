package com.bazaarhint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 바자 주문 갱신 도우미 (표시 + 클릭 한 번 = 명령 한 번. 자동 취소/재등록은 하지 않음)
 *  - 내 주문은 '주문 관리' 창을 열 때와 채팅 메시지(주문 생성/체결)로 파악합니다.
 *  - 대시보드(/api/quotes)의 최고 구매 주문/최저 판매 주문과 비교해 앞지름을 감지하면 채팅에 버튼을 띄웁니다.
 *      [바자 열기] -> /bz <아이템>   (바자 검색 화면만 열어 줌)
 *      [새 가격 복사] -> 클립보드에 새 가격 (가격 입력 창에서 Ctrl+V)
 */
public final class OrderHelper {
    record Order(boolean buy, String name, double price) {}

    static volatile List<Order> orders = List.of();
    static volatile Map<String, double[]> quotes = Map.of();  // 소문자 이름 -> [즉시 구매가, 즉시 판매가]
    static volatile Set<Integer> outbidSlots = Set.of();      // 주문 관리 창에서 갱신 필요한 칸
    static volatile int outdatedCount = 0;
    static volatile boolean outbidOn = true;  // 설정 탭에서 켜고 끔
    static final Map<String, Long> notified = new ConcurrentHashMap<>();
    static long lastScan = 0, lastDiag = 0;
    static int lastCount = -1, unmatched = 0;
    static volatile String missingName = "";

    // 형식이 조금 달라도 잡히도록 느슨하게 (대소문자 무시, 공백/느낌표 선택)
    static final Pattern SETUP = Pattern.compile("(?i)\\[Bazaar\\]\\s*(Buy Order|Sell Offer)\\s*Setup!?\\s*([\\d,]+)x\\s+(.+?)\\s+for\\s+([\\d,.]+)\\s+coins");
    static final Pattern FILLED = Pattern.compile("(?i)\\[Bazaar\\].*?(Buy Order|Sell Offer).*?([\\d,]+)x\\s+(.+?)\\s+(?:was|has been)\\s+filled");
    // 위 형식이 아니어도 '[Bazaar] ... buy/sell ... Nx 이름 for N coins' 모양이면 새 주문으로 봄
    static final Pattern GENERIC = Pattern.compile("(?i)\\b(buy|sell)\\b.*?([\\d,]+)x\\s+(.+?)\\s+for\\s+([\\d,.]+)\\s+coins");
    static final Pattern HINT = Pattern.compile("(?i)^\\W*(buy|sell)\\b");
    static final Pattern UNIT = Pattern.compile("(?i)Price per unit:\\s*([\\d,.]+)");
    static final Pattern WORTH = Pattern.compile("(?i)Worth\\s+([\\d,.]+)\\s+coins");
    static final Pattern AMOUNT = Pattern.compile("(?i)Order amount:\\s*([\\d,]+)");
    static final Pattern TITLE = Pattern.compile("^\\W*(BUY|SELL)\\s+(.+)$");

    private OrderHelper() {}

    static void init() {
        ClientReceiveMessageEvents.GAME.register(OrderHelper::onChat);
    }

    /** 이름 비교용: 소문자 + 영문/숫자만 (표기 차이 무시) */
    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** 인식 실패 내용을 게임 폴더의 bazaar_hint_debug.txt 에 저장 (이 파일 내용을 보내주시면 형식에 맞게 고칠 수 있어요) */
    static void dump(String text) {
        try {
            java.nio.file.Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("bazaar_hint_debug.txt");
            java.nio.file.Files.writeString(p, java.time.LocalTime.now().withNano(0) + " " + text + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 저장 실패는 무시
        }
    }

    static String raw(String name, ItemLore lore) {
        List<String> ls = new ArrayList<>();
        if (lore != null) {
            for (Component c : lore.lines()) {
                if (ls.size() < 10) ls.add(clean(c.getString()));
            }
        }
        return name + " | " + String.join(" / ", ls);
    }

    static String clean(String s) {
        return s.replaceAll("§.", "").trim();
    }

    static double num(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void say(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text).withStyle(color));
        });
    }

    /** 오른쪽 위 리스트에 표시되는 진단 줄: 어느 단계가 막혔는지 한눈에 보이게 */
    static String statusLine() {
        String t = "주문 " + orders.size() + "개 인식 · 시세 " + (quotes.isEmpty() ? "연결 안 됨" : quotes.size() + "개");
        return missingName.isEmpty() ? t : t + " · 시세에 없는 이름: " + missingName;  // 최대 3개
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%,.1f", v);
    }

    // ---------------------------------------------------------------- 내 주문 파악
    static int logged = 0;

    static void logChat(String s, boolean ok) {
        if (logged++ < 150) dump("CHAT[" + (ok ? "OK" : "??") + "] " + s);
        if (!ok && unmatched++ < 3) {
            say("[바자 갱신 진단] 인식 못한 바자 메시지 → " + s + "  (bazaar_hint_debug.txt 에도 저장)", ChatFormatting.GRAY);
        }
    }

    static void onChat(Component message, boolean overlay) {
        if (overlay) return;
        String s = clean(message.getString());
        if (!s.startsWith("[Bazaar]")) return;
        Matcher m = SETUP.matcher(s);
        boolean setup = m.find();
        if (!setup) {
            m = GENERIC.matcher(s);
            setup = m.find();
        }
        if (setup) {  // 새 주문 생성
            double amount = num(m.group(2)), total = num(m.group(4));
            if (amount > 0) {
                List<Order> l = new ArrayList<>(orders);
                l.add(new Order(m.group(1).toLowerCase(Locale.ROOT).startsWith("buy"), m.group(3).trim(),
                        Math.round(total / amount * 10) / 10.0));
                orders = l;
                check();
            }
            logChat(s, true);
            return;
        }
        m = FILLED.matcher(s);
        if (m.find()) {  // 체결 -> 목록에서 제거
            boolean buy = m.group(1).toLowerCase(Locale.ROOT).startsWith("buy");
            String name = m.group(3).trim();
            List<Order> l = new ArrayList<>(orders);
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i).buy() == buy && norm(l.get(i).name()).equals(norm(name))) {
                    l.remove(i);
                    break;
                }
            }
            orders = l;
            logChat(s, true);
            return;
        }
        logChat(s, false);
    }

    /** '주문 관리' 창의 상품 칸에서 내 주문(가격/종류)을 읽어 목록을 새로 맞춥니다 (1초에 한 번). */
    static void diag(List<String> bad, long now, int n) {
        if (now - lastDiag <= 60_000) return;
        lastDiag = now;
        dump("ORDERS_GUI recognized=" + n + " :: " + String.join(" || ", bad));
        say("[바자 갱신 진단] 주문 창에서 " + n + "개만 읽었어요. 더 있어야 한다면 bazaar_hint_debug.txt(게임 폴더) 내용을 알려주세요.",
                ChatFormatting.GRAY);
    }

    static void scan(ChestMenu menu) {
        long now = System.currentTimeMillis();
        if (now - lastScan < 1000) return;
        lastScan = now;
        List<Order> list = new ArrayList<>();
        Map<Integer, Order> bySlot = new HashMap<>();
        List<String> bad = new ArrayList<>();
        boolean any = false, hint = false, badOrder = false;
        int count = Math.min(menu.getRowCount() * 9, menu.slots.size());
        for (int i = 0; i < count; i++) {
            ItemStack st = menu.slots.get(i).getItem();
            if (st.isEmpty()) continue;
            String name = clean(st.getHoverName().getString());
            if (name.isEmpty()) continue;  // 장식용 유리판 등
            any = true;
            ItemLore lore = st.get(DataComponents.LORE);
            String r = raw(name, lore), low = r.toLowerCase(Locale.ROOT);
            Matcher t = TITLE.matcher(name);
            boolean titled = t.find();
            boolean orderish = titled || HINT.matcher(name).find() || low.contains("price per unit") || low.contains("order amount");
            if (orderish) hint = true;
            double price = 0, worth = 0, amount = 0;
            boolean full = false;
            if (titled && lore != null) {
                for (Component c : lore.lines()) {
                    String l = clean(c.getString());
                    Matcher u = UNIT.matcher(l), w = WORTH.matcher(l), am = AMOUNT.matcher(l);
                    if (u.find()) price = num(u.group(1));
                    if (w.find()) worth = num(w.group(1));
                    if (am.find()) amount = num(am.group(1));
                    if (l.startsWith("Filled") && l.contains("100%")) full = true;
                }
            }
            if (price <= 0 && worth > 0 && amount > 0) price = Math.round(worth / amount * 10) / 10.0;  // 개당 가격 줄이 없을 때 대비
            if (titled && full) continue;  // 체결 완료(수령 대기)는 앞지름 대상 아님
            if (!titled || price <= 0) {
                if (orderish) badOrder = true;
                if (bad.size() < 6) bad.add(r);
                continue;
            }
            Order o = new Order(t.group(1).equals("BUY"), t.group(2).trim(), price);
            list.add(o);
            bySlot.put(i, o);
        }
        if (!any) return;  // 아직 내용이 도착하지 않음
        if (list.isEmpty() && hint) {
            // 주문처럼 보이는 칸은 있는데 하나도 못 읽음 = 읽기 실패. 채팅으로 알고 있던 주문을 지우지 않고 그대로 둠
            diag(bad, now, 0);
            return;
        }
        if (badOrder) diag(bad, now, list.size());
        orders = list;  // 읽기 성공(또는 정말 주문 0개)이면 창 내용으로 맞춤
        if (list.size() != lastCount) {
            lastCount = list.size();
            say("[바자 갱신] 내 주문 " + list.size() + "개를 인식했어요", ChatFormatting.GRAY);
        }
        Set<Integer> out = new HashSet<>();
        for (Map.Entry<Integer, Order> e : bySlot.entrySet()) {
            if (!Double.isNaN(better(e.getValue()))) out.add(e.getKey());
        }
        outbidSlots = outbidOn ? out : Set.of();
        check();
    }

    static void drawOutbid(GuiGraphicsExtractor g, ChestMenu menu, int left, int top) {
        for (int idx : outbidSlots) {
            if (idx >= menu.slots.size()) continue;
            Slot s = menu.slots.get(idx);
            int x = left + s.x, y = top + s.y;
            g.fill(x, y, x + 16, y + 16, 0x55FF4040);
            g.outline(x - 1, y - 1, 18, 18, 0xFFFF5555);
        }
    }

    // ---------------------------------------------------------------- 앞지름 감지
    /** 앞지른 주문이 있으면 새로 걸 가격, 없으면 NaN. 구매: 최고 구매 주문이 내 가격보다 높음 / 판매: 최저 판매 주문이 내 가격보다 낮음. */
    static double better(Order o) {
        double[] q = quotes.get(norm(o.name()));
        if (q == null) return Double.NaN;
        double ask = q[0], bid = q[1];
        if (o.buy() && bid > o.price() + 0.05) return Math.round((bid + 0.1) * 10) / 10.0;
        if (!o.buy() && ask < o.price() - 0.05) return Math.round((ask - 0.1) * 10) / 10.0;
        return Double.NaN;
    }

    static void pollQuotes(HttpClient http) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BazaarHintClient.BASE + "/api/quotes"))
                    .timeout(Duration.ofSeconds(8)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;
            JsonObject q = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("q");
            Map<String, double[]> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : q.entrySet()) {
                JsonArray a = e.getValue().getAsJsonArray();
                map.put(norm(e.getKey()), new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble()});
            }
            quotes = map;
            check();
        } catch (Exception ignored) {
            // 대시보드가 꺼져 있으면 조용히 건너뜀
        }
    }

    static void check() {
        if (!outbidOn) {
            outdatedCount = 0;
            return;
        }
        int n = 0;
        long now = System.currentTimeMillis();
        Set<String> miss = new java.util.LinkedHashSet<>();
        for (Order o : orders) {
            if (!quotes.isEmpty() && !quotes.containsKey(norm(o.name())) && miss.size() < 3) miss.add(o.name());
            double np = better(o);
            if (Double.isNaN(np)) continue;
            n++;
            String key = o.buy() + o.name() + np;
            Long last = notified.get(key);
            if (last != null && now - last < 300_000) continue;  // 같은 알림은 5분에 한 번
            notified.put(key, now);
            notifyOutbid(o, np);
        }
        outdatedCount = n;
        missingName = String.join(", ", miss);
    }

    static MutableComponent button(String text, ChatFormatting color, ClickEvent ev) {
        return Component.literal(text).withStyle(color).withStyle(s -> s.withClickEvent(ev));
    }

    static void notifyOutbid(Order o, double np) {
        double[] q = quotes.get(norm(o.name()));
        double now = o.buy() ? q[1] : q[0];
        MutableComponent msg = Component.literal("[바자 갱신] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal((o.buy() ? "구매 주문 " : "판매 주문 ") + o.name() + " · 내 " + fmt(o.price())
                        + " → 현재 " + (o.buy() ? "최고 구매 " : "최저 판매 ") + fmt(now) + "  ").withStyle(ChatFormatting.YELLOW))
                .append(button("[바자 열기]", ChatFormatting.GREEN, new ClickEvent.RunCommand("/bz " + o.name())))
                .append(Component.literal(" "))
                .append(button("[새 가격 " + fmt(np) + " 복사]", ChatFormatting.AQUA,
                        new ClickEvent.CopyToClipboard(String.format(Locale.ROOT, "%.1f", np))));
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(msg);
        });
    }
}
