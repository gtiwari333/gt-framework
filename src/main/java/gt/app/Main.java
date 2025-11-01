package gt.app;

public class Main {
    static void main() throws Exception {
        DI container = new DI(Main.class.getPackageName()).initialize();

        MyController controller = container.getComponent(MyController.class);
        controller.doSomething();
    }
}
