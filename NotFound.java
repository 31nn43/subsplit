// Thrown when an id doesn't exist; the server turns it into an HTTP 404.
public class NotFound extends RuntimeException {
    public NotFound(String message) { super(message); }
}
