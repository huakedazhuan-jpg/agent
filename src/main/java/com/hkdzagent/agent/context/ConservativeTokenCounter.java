package com.hkdzagent.agent.context;

import org.springframework.stereotype.Component;

@Component
public class ConservativeTokenCounter implements TokenCounter {

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int nonAscii = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (codePoint <= 0x7f) {
                ascii++;
            } else {
                nonAscii++;
            }
            offset += Character.charCount(codePoint);
        }
        return nonAscii + (ascii + 3) / 4;
    }
}
