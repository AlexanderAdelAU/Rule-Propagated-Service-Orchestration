package org.btsn.exceptions;

/**
 * Exception for token parsing specific errors
 */
public class TokenParsingException extends JsonParsingException {
    
    private final String tokenFormat;
    private final String receivedFormat;
    
    public TokenParsingException(String message, String tokenFormat, String receivedFormat) {
        super(message);
        this.tokenFormat = tokenFormat;
        this.receivedFormat = receivedFormat;
    }
    
    public TokenParsingException(String message, Throwable cause, String tokenFormat, String receivedFormat) {
        super(message, cause);
        this.tokenFormat = tokenFormat;
        this.receivedFormat = receivedFormat;
    }
    
    public String getTokenFormat() { 
        return tokenFormat; 
    }
    
    public String getReceivedFormat() { 
        return receivedFormat; 
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TokenParsingException: ").append(getMessage());
        
        if (tokenFormat != null) {
            sb.append(" (expected: ").append(tokenFormat);
            if (receivedFormat != null) {
                sb.append(", received: ").append(receivedFormat);
            }
            sb.append(")");
        }
        
        return sb.toString();
    }
}