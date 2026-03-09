package io.github.rocketbunny727.swiftmapper.utils.greeting;

public class GreetingManager {
    private static final String name =
            """
                       _____         _ ______  __  ___                          \s
                      / ___/      __(_) __/ /_/  |/  /___ _____  ____  ___  _____
                      \\__ \\ | /| / / / /_/ __/ /|_/ / __ `/ __ \\/ __ \\/ _ \\/ ___/
                     ___/ / |/ |/ / / __/ /_/ /  / / /_/ / /_/ / /_/ /  __/ /   \s
                    /____/|__/|__/_/_/  \\__/_/  /_/\\__,_/ .___/ .___/\\___/_/    \s
                                                       /_/   /_/                \s
                    """;

    private static final String welcome =
            """
                     _    _      _                         \s
                    | |  | |    | |                        \s
                    | |  | | ___| | ___ ___  _ __ ___   ___\s
                    | |/\\| |/ _ \\ |/ __/ _ \\| '_ ` _ \\ / _ \\
                    \\  /\\  /  __/ | (_| (_) | | | | | |  __/
                     \\/  \\/ \\___|_|\\___\\___/|_| |_| |_|\\___|
                                                           \s
                                                           \s
                    """;

    private static final String to =
            """
                      __         \s
                    _/  |_  ____ \s
                    \\   __\\/  _ \\\s
                     |  | (  <_> )
                     |__|  \\____/\s
                                 \s
                    """;

    private static final String logo =
            """
                    
                    """;

    public static void printGreeting() {
        System.out.println(name + "\n");
    }
}
