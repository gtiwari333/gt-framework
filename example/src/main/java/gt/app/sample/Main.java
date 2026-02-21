package gt.app.sample;

import com.sun.net.httpserver.HttpServer;
import gt.app.DI;

public class Main {
    static void main() throws Exception {
        HttpServer a;
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            DI container = new DI(Main.class.getPackageName()).initialize();

            MyController controller = container.getComponent(MyController.class);
            controller.doSomething();
            long end = System.nanoTime();
            System.out.println((end - start) + "ns");
        }
    }
}
