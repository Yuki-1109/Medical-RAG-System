package com.medicalrag.knowledgebase;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KnowledgeBaseServiceTest {
    @Test void unicodeWhitespaceCannotBecomeAnUnnamedLibrary() {
        for (String value : new String[] {"", "   ", "\u00a0\u3000", "\n\t", "a\u0000b", "x".repeat(101)}) {
            assertThatThrownBy(() -> KnowledgeBaseService.normalizeName(value)).isInstanceOf(KnowledgeBaseException.class);
        }
        assertThat(KnowledgeBaseService.normalizeName("\u3000 心血管研究 \u00a0")).isEqualTo("心血管研究");
    }
    @Test void optionalDescriptionAllowsParagraphsButNotInvalidControlCharacters() {
        assertThat(KnowledgeBaseService.normalizeDescription(null)).isEmpty();
        assertThat(KnowledgeBaseService.normalizeDescription("段落一\n段落二")).contains("\n");
        assertThatThrownBy(() -> KnowledgeBaseService.normalizeDescription("x".repeat(2001))).isInstanceOf(KnowledgeBaseException.class);
        assertThatThrownBy(() -> KnowledgeBaseService.normalizeDescription("坏\u0000值")).isInstanceOf(KnowledgeBaseException.class);
    }
    @Test void idsKeepBigintPrecisionAndRejectNonCanonicalValues() {
        assertThat(KnowledgeBaseService.parseId("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        for (String value : new String[] {"-1", "0", "01", "+1", "1.0", "x", "9223372036854775808"}) {
            assertThatThrownBy(() -> KnowledgeBaseService.parseId(value)).isInstanceOf(KnowledgeBaseException.class);
        }
    }
}
