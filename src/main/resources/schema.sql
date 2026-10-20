SET @chat_messages_content_ngram_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_messages'
      AND index_name = 'idx_chat_messages_content_ngram'
);

SET @chat_messages_content_ngram_index_ddl = IF(
    @chat_messages_content_ngram_index_exists = 0,
    'ALTER TABLE chat_messages ADD FULLTEXT INDEX idx_chat_messages_content_ngram (content) WITH PARSER ngram',
    'SELECT 1'
);

PREPARE add_chat_messages_content_ngram_index FROM @chat_messages_content_ngram_index_ddl;
EXECUTE add_chat_messages_content_ngram_index;
DEALLOCATE PREPARE add_chat_messages_content_ngram_index;
