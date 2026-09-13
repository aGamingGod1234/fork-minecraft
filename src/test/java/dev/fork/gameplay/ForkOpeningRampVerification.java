package dev.fork.gameplay;
import java.util.List;
import dev.agaminggod.arenaagents.client.camera.*;
public final class ForkOpeningRampVerification {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        var frames=List.of(new CameraKeyframe(0,10,15,20,40,90),new CameraKeyframe(26,10,430,20,40,90),new CameraKeyframe(45,10,430,20,40,90),new CameraKeyframe(56,10,430,20,40,17.5f),new CameraKeyframe(62,10,430,20,40,17.5f));
        var p=new CameraPath("ramp",frames,"smootherstep");double previous=15,maxStep=0;
        for(int i=0;i<=78;i++){var at=p.sample(i/3.0);require(at.y()>=previous-1e-9&&at.y()<=430,"Rise reversed or overshot");require(at.x()==10&&at.z()==20&&at.yaw()==40&&at.pitch()==90,"Rise changed position axis or angle");maxStep=Math.max(maxStep,at.y()-previous);previous=at.y();}
        require(maxStep>20*(p.sample(1.0/3).y()-15),"Start lacks a speed ramp");
        require(maxStep>20*(430-p.sample(26-1.0/3).y()),"End lacks deceleration");
        for(double t=26;t<=45;t+=.1)require(p.sample(t).equals(p.sample(26)),"Wide hold moved");
        float pitch=90;for(double t=45;t<=56;t+=.1){var at=p.sample(t);require(at.x()==10&&at.y()==430&&at.z()==20&&at.yaw()==40,"Sky tilt changed heading");require(at.pitch()<=pitch&&at.pitch()>=17.5,"Tilt reversed");pitch=at.pitch();}
        for(double t=56;t<=62;t+=.1)require(p.sample(t).equals(p.sample(56)),"Sky hold moved");
        var legacy=new CameraPath("legacy",frames);var explicit=new CameraPath("legacy",frames,"catmull_rom");for(double t=0;t<62;t+=.13)require(legacy.sample(t).equals(explicit.sample(t)),"Legacy default changed");
        require(p.append(new CameraKeyframe(63,10,430,20,40,17.5f)).interpolation().equals("smootherstep"),"Append lost mode");
        boolean rejected=false;try{new CameraPath("bad",frames,"unknown");}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Invalid mode accepted");
        System.out.println("PASS: one-axis top-down ramp, monotonic motion, acceleration/deceleration, fixed-heading tilt, exact holds and legacy default");
    }
}
