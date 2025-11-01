package gt.app.sample;

import gt.app.DI;

public class Main {
    static void main() throws Exception {
        DI container = new DI(Main.class.getPackageName()).initialize();

        MyController controller = container.getComponent(MyController.class);
        controller.doSomething();
    }
}
