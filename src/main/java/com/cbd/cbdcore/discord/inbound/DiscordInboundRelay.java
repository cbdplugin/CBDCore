package com.cbd.cbdcore.discord.inbound;

import com.cbd.cbdcore.discord.inbound.DiscordInboundMessageMapper.InboundMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyFormat;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 정제된 디스코드 메시지를 인게임 채팅으로 방송한다.
 *
 * <p>서식 문자열(예: {@code &7[디스코드] &f%name% &7: &f%message%})의 <b>고정 부분만</b>
 * '&' 색상 코드로 해석하고, 디스코드 사용자에게서 온 이름/본문은 {@link Component#text(String)}로
 * 리터럴 삽입한다. 이렇게 하면 디스코드 사용자가 이름이나 메시지에 '&' 색상 코드를 넣어도
 * 인게임 채팅의 색을 조작(인젝션)할 수 없다.</p>
 */
public final class DiscordInboundRelay {

    private static final Pattern PLACEHOLDER = Pattern.compile("%name%|%message%");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;

    public DiscordInboundRelay(Plugin plugin) {
        this.plugin = plugin;
    }

    public void broadcast(InboundMessage message, String format) {
        Component component = render(format, message.name(), message.content());
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(component));
    }

    /**
     * 서식 문자열을 인젝션에 안전한 컴포넌트로 조립한다. 치환값(name/message)에는
     * 바로 앞 서식 조각의 색상만 물려주어(디코레이션은 물려주지 않음) 리터럴로 삽입한다.
     */
    static Component render(String format, String name, String message) {
        Component result = Component.empty();
        Matcher matcher = PLACEHOLDER.matcher(format);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                result = result.append(LEGACY.deserialize(format.substring(last, matcher.start())));
            }
            String value = "%name%".equals(matcher.group()) ? name : message;
            Component valueComponent = Component.text(value);
            TextColor inherited = trailingColor(format.substring(0, matcher.start()));
            if (inherited != null) {
                valueComponent = valueComponent.color(inherited);
            }
            result = result.append(valueComponent);
            last = matcher.end();
        }
        if (last < format.length()) {
            result = result.append(LEGACY.deserialize(format.substring(last)));
        }
        return result;
    }

    /**
     * 주어진 '&' 서식 문자열에서 마지막으로 적용된 색상 코드를 찾아 그 색을 돌려준다.
     * 색상 코드가 없거나 '&r'로 리셋된 뒤라면 null.
     */
    static TextColor trailingColor(String legacy) {
        TextColor color = null;
        for (int i = 0; i + 1 < legacy.length(); i++) {
            if (legacy.charAt(i) != '&') {
                continue;
            }
            char code = Character.toLowerCase(legacy.charAt(i + 1));
            LegacyFormat format = LegacyComponentSerializer.parseChar(code);
            if (format == null) {
                continue;
            }
            if (format.reset()) {
                color = null;
            } else if (format.color() != null) {
                color = format.color();
            }
            i++;
        }
        return color;
    }
}
