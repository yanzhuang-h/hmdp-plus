package org.javaup.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证多轮对话的「记忆组件」是否正确工作。
 *
 * <p>这部分不依赖大模型，可以独立验证：
 * <ul>
 *   <li>按 conversationId 隔离不同会话；</li>
 *   <li>同一会话内消息按顺序累积；</li>
 *   <li>超过窗口上限时丢弃最旧的消息（保证上下文不无限增长）。</li>
 * </ul>
 *
 * <p>说明：「模型是否利用记忆」必须用真实模型验证；本测试只证明记忆的存取与裁剪逻辑正确。
 */
class ChatMemoryTest {

    @Test
    @DisplayName("不同 sessionId 的记忆互相隔离")
    void memoryIsolatedByConversationId() {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();

        memory.add("session-A", new UserMessage("A 的问题"));
        memory.add("session-B", new UserMessage("B 的问题"));

        var a = memory.get("session-A");
        var b = memory.get("session-B");

        assertEquals(1, a.size(), "session-A 应有 1 条消息");
        assertEquals(1, b.size(), "session-B 应有 1 条消息");
        assertEquals("A 的问题", a.get(0).getText());
        assertEquals("B 的问题", b.get(0).getText());

        System.out.println("[记忆] 会话隔离验证通过：A=" + a.size() + " 条，B=" + b.size() + " 条");
    }

    @Test
    @DisplayName("同一会话内多轮消息按顺序累积")
    void messagesAccumulateInOrder() {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();
        String sid = "session-order";

        memory.add(sid, new UserMessage("第1轮提问"));
        memory.add(sid, new AssistantMessage("第1轮回答"));
        memory.add(sid, new UserMessage("第2轮提问"));

        var msgs = memory.get(sid);
        assertEquals(3, msgs.size());
        assertEquals("第1轮提问", msgs.get(0).getText());
        assertEquals("第1轮回答", msgs.get(1).getText());
        assertEquals("第2轮提问", msgs.get(2).getText());

        System.out.println("[记忆] 多轮累积验证通过：共 " + msgs.size() + " 条，顺序正确");
    }

    @Test
    @DisplayName("超过窗口上限时裁剪最旧消息，避免上下文无限增长")
    void windowTruncatesOldestMessages() {
        int max = 4;
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(max).build();
        String sid = "session-window";

        for (int i = 1; i <= 6; i++) {
            memory.add(sid, new UserMessage("消息-" + i));
        }

        var msgs = memory.get(sid);
        assertTrue(msgs.size() <= max,
                "消息数不应超过窗口上限 " + max + "，实际=" + msgs.size());

        // 最旧的消息应被丢弃，最新的应保留
        String dump = msgs.toString();
        assertTrue(dump.contains("消息-6"), "最新消息应保留");
        assertTrue(!dump.contains("消息-1"), "最旧消息应被裁剪");

        System.out.println("[记忆] 窗口裁剪验证通过：写入 6 条，保留 " + msgs.size()
                + " 条，最新=" + msgs.get(msgs.size() - 1).getText());
    }
}
