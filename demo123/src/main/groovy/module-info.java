module team.themoment.demo123 {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.apache.groovy;

    requires com.dlsc.formsfx;

    opens team.themoment.demo123 to javafx.fxml;
    exports team.themoment.demo123;
}