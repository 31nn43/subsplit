public class accessError extends RuntimeException{
    public final int status;

    public accessError(int status, String message){
        super(message);
        this.status = status;
    }
}
