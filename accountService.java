// Sign-up and login rules. No HTTP in here.
public class accountService {
    private final Store store;

    public accountService(Store store) { this.store = store; }

    public User signUp(String name, String email, String password) {
        email = email.trim().toLowerCase();
        if (!email.contains("@")) throw new IllegalArgumentException("Enter a valid email address");
        if (password.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters");
        if (store.findUserByEmail(email).isPresent()) throw new IllegalArgumentException("That email is already registered");

        String salt = Auth.newSalt();
        return store.save(new User(store.nextId(), name.trim(), email, salt, Auth.hash(password, salt)));
    }

    public User login(String email, String password) {
        User user = store.findUserByEmail(email.trim().toLowerCase()).orElse(null);
        if (user == null || !Auth.matches(password, user.salt(), user.hash()))
            throw new accessError(401, "Wrong email or password");
        return user;
    }
}
