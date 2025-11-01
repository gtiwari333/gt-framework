package gt.app;

@Component
public class MyController {

    private final MyService myService;
    private final MyService2 myService2;

    @Autowired
    public MyController(MyService myService, MyService2 myService2) {
        this.myService = myService;
        this.myService2 = myService2;
    }

    public void doSomething() {
        System.out.println("Controller doing something: " + myService.sayHello());
        System.out.println("Controller doing something: " + myService2.sayHello());
    }
}
