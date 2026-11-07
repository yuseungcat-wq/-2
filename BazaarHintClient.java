package com.bazaarhint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 표시 전용 모드: 로컬 대시보드(bazaar_web.py)의 /api/mod 를 읽어
 *  1) 화면 오른쪽 위에 추천 품목 리스트를 그리고
 *  2) 바자 창(체스트 GUI)에서 '사야 할 재료'를 초록, '만들어 팔 완성품'을 금색으로 강조합니다.
 * 클릭/구매/판매를 대신하지 않습니다.
 */
public class BazaarHintClient implements ClientModInitializer {
    // 바꾸고 싶으면 JVM 옵션 -Dbazaarhint.url=... 로 지정 (tab=craft|chain|spread|npc, n=표시 개수)
    static final String BASE = System.getProperty("bazaarhint.base", "http://localhost:8000");
    static final String URL = System.getProperty("bazaarhint.url", BASE + "/api/mod?tab=craft&n=8");
    static final int REFRESH_MS = 15_000;

    record Pick(String name, double profit, double margin, List<String> buy) {}

    static volatile List<Pick> picks = List.of();
    static volatile Set<String> buyNames = Set.of();   // 소문자
    static volatile Set<String> sellNames = Set.of();  // 소문자
    static volatile String status = "대시보드 연결 중...";

    // 설정 탭(대시보드)에서 켜고 끄는 기능들 — 15초마다 /api/settings 에서 읽어옴. 대시보드가 꺼져 있으면 전부 켜진 기본값
    static volatile boolean hudOn = true, statusOn = true, highlightOn = true;
    static volatile String hudTab = "craft";
    static volatile int hudCount = 8;
    static volatile int refreshSec = 5;  // 설정 탭에서 바꿈

    static String picksUrl() {
        String custom = System.getProperty("bazaarhint.url");
        return custom != null ? custom : BASE + "/api/mod?tab=" + hudTab + "&n=" + hudCount;
    }

    static void pollSettings(HttpClient http) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/settings")).timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;
            JsonObject m = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("mod");
            hudOn = m.get("hud").getAsBoolean();
            statusOn = m.get("status").getAsBoolean();
            highlightOn = m.get("highlight").getAsBoolean();
            OrderHelper.outbidOn = m.get("outbid").getAsBoolean();
            hudTab = m.get("tab").getAsString();
            hudCount = m.get("count").getAsInt();
            if (m.has("refresh")) refreshSec = Math.max(2, m.get("refresh").getAsInt());
        } catch (Exception ignored) {
            // 대시보드가 꺼져 있거나 예전 버전이면 기본값 유지
        }
    }

    @Override
    public void onInitializeClient() {
        OrderHelper.init();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        Thread t = new Thread(() -> {
            while (true) {
                pollSettings(http);
                poll(http);
                OrderHelper.pollQuotes(http);
                try {
                    Thread.sleep(refreshSec * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "bazaar-hint-poll");
        t.setDaemon(true);
        t.start();

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("bazaar_hint", "pick_list"), BazaarHintClient::drawList);

        ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenEvents.afterExtract(screen).register(BazaarHintClient::highlight);
            }
        });
    }

    // ---------------------------------------------------------------- 데이터
    static void poll(HttpClient http) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(picksUrl())).timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
            JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            List<Pick> list = new ArrayList<>();
            Set<String> buys = new HashSet<>();
            Set<String> sells = new HashSet<>();
            for (JsonElement e : root.getAsJsonArray("picks")) {
                JsonObject o = e.getAsJsonObject();
                List<String> buy = new ArrayList<>();
                for (JsonElement b : o.getAsJsonArray("buy")) {
                    JsonArray a = b.getAsJsonArray();
                    String n = a.get(0).getAsString();
                    buy.add(a.get(1).getAsInt() + "x " + n);
                    buys.add(n.toLowerCase(Locale.ROOT));
                }
                String name = o.get("name").getAsString();
                sells.add(name.toLowerCase(Locale.ROOT));
                list.add(new Pick(name, o.get("profit").getAsDouble(), o.get("margin").getAsDouble(), buy));
            }
            picks = list;
            buyNames = buys;
            sellNames = sells;
            status = list.isEmpty() ? "조건에 맞는 품목 없음" : "";
        } catch (Exception ex) {
            status = "대시보드 연결 실패 (bazaar_web.py 실행 확인)";
        }
    }

    static String clean(String s) {
        return s.replaceAll("§.", "").trim().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- 오른쪽 위 리스트
    static void drawList(GuiGraphicsExtractor g, DeltaTracker dt) {
        if (!hudOn) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        List<Pick> list = picks;
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        lines.add("바자 플립 TOP " + list.size());
        colors.add(0xFFF5C451);
        if (statusOn) {
            lines.add(OrderHelper.statusLine());
            colors.add(0xFF9DB0A5);
        }
        if (OrderHelper.outdatedCount > 0) {
            lines.add("⚠ 갱신 필요 주문 " + OrderHelper.outdatedCount + "개");
            colors.add(0xFFFFA040);
        }
        if (!status.isEmpty()) {
            lines.add(status);
            colors.add(0xFFFF8888);
        }
        int rank = 1;
        for (Pick p : list) {
            lines.add(rank++ + ". " + p.name() + "  +" + String.format("%,.0f", p.profit())
                    + " (" + String.format("%.1f", p.margin()) + "%)");
            colors.add(0xFF7CF29C);
            if (!p.buy().isEmpty()) {
                String buy = String.join(", ", p.buy());
                lines.add("   구매: " + (buy.length() > 48 ? buy.substring(0, 47) + "…" : buy));
                colors.add(0xFFB5C4BB);
            }
        }
        int maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, font.width(l));
        int pad = 4, lineH = font.lineHeight + 2;
        int right = mc.getWindow().getGuiScaledWidth() - 4;
        int x1 = right, x0 = x1 - maxW - pad * 2, y0 = 4, y1 = y0 + lines.size() * lineH + pad * 2 - 2;
        g.fill(x0, y0, x1, y1, 0x90000000);
        int y = y0 + pad;
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), x0 + pad, y, colors.get(i), true);
            y += lineH;
        }
    }

    // ---------------------------------------------------------------- 바자 창 강조
    static void highlight(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float tickDelta) {
        if (!(screen instanceof AbstractContainerScreen<?> cs) || !(cs.getMenu() instanceof ChestMenu menu)) return;
        String title = screen.getTitle().getString();
        // 체스트형 GUI 기본 크기: 폭 176, 높이 114 + 줄수*18
        int left = (screen.width - 176) / 2;
        int top = (screen.height - (114 + menu.getRowCount() * 18)) / 2;
        if (title.contains("Orders")) {  // '주문 관리' 창: 내 주문 읽기 + 앞지름당한 칸을 빨강으로
            OrderHelper.scan(menu);
            OrderHelper.drawOutbid(g, menu, left, top);
            return;
        }
        if (!highlightOn || (!title.contains("Bazaar") && !title.contains("➜"))) return;  // 바자 창으로 보이는 경우만
        Set<String> buy = buyNames, sell = sellNames;
        if (buy.isEmpty() && sell.isEmpty()) return;
        int count = menu.getRowCount() * 9;  // 위쪽 상품 칸만 (내 인벤토리 제외)
        for (int i = 0; i < count && i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (!s.hasItem()) continue;
            String n = clean(s.getItem().getHoverName().getString());
            boolean b = buy.contains(n), c = sell.contains(n);
            if (!b && !c) continue;
            int x = left + s.x, y = top + s.y;
            int line = b ? 0xFF3FE06E : 0xFFF5C451;
            g.fill(x, y, x + 16, y + 16, b ? 0x403FE06E : 0x40F5C451);
            g.outline(x - 1, y - 1, 18, 18, line);
        }
    }
}
