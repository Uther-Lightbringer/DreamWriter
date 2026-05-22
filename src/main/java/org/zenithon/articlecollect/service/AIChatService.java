package org.zenithon.articlecollect.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.zenithon.articlecollect.entity.ChatMessage;
import org.zenithon.articlecollect.entity.ChatSession;
import org.zenithon.articlecollect.repository.ChatMessageRepository;
import org.zenithon.articlecollect.repository.ChatSessionRepository;

import java.util.*;

/**
 * AI 聊天服务
 * 管理聊天会话和历史记录
 */
@Service
public class AIChatService {

    private static final Logger logger = LoggerFactory.getLogger(AIChatService.class);

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;

    public AIChatService(ChatMessageRepository chatMessageRepository,
                         ChatSessionRepository chatSessionRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatSessionRepository = chatSessionRepository;
    }

    // ===== 会话管理 =====

    public ChatSession createSession(String title) {
        ChatSession session = new ChatSession(title != null ? title : "新对话");
        return chatSessionRepository.save(session);
    }

    public List<ChatSession> getAllSessions() {
        return chatSessionRepository.findAllByOrderByUpdateTimeDesc();
    }

    public ChatSession getSession(Long sessionId) {
        return chatSessionRepository.findById(sessionId).orElse(null);
    }

    public ChatSession updateSessionTitle(Long sessionId, String title) {
        ChatSession session = chatSessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            session.setTitle(title);
            return chatSessionRepository.save(session);
        }
        return null;
    }

    public void deleteSession(Long sessionId) {
        chatMessageRepository.deleteBySessionId(sessionId);
        chatSessionRepository.deleteById(sessionId);
    }

    public void touchSession(Long sessionId) {
        ChatSession session = chatSessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            session.setUpdateTime(java.time.LocalDateTime.now());
            chatSessionRepository.save(session);
        }
    }

    // ===== 消息管理 =====

    public ChatMessage saveMessage(Long sessionId, String role, String content) {
        ChatMessage message = new ChatMessage(sessionId, role, content);
        return chatMessageRepository.save(message);
    }

    public List<ChatMessage> getHistory(Long sessionId) {
        return chatMessageRepository.findBySessionIdOrderByCreateTimeAsc(sessionId);
    }

    public void clearHistory(Long sessionId) {
        chatMessageRepository.deleteBySessionId(sessionId);
    }
}
