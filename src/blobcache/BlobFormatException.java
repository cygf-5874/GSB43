package blobcache;

/**
 * blob 解码 / 校验失败时抛出的受检异常。
 *
 * <p>{@link #code()} 给出机器可读的错误码，取值见 README「错误码」一节。
 */
public class BlobFormatException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;

    public BlobFormatException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    /** 机器可读的错误码。 */
    public String code() {
        return code;
    }
}
