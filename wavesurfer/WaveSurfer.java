package sigmaphi;

import robocode.*;
import robocode.util.Utils;

import java.awt.geom.*;
import java.util.ArrayList;
import java.awt.Color;


public class WaveSurfer extends AdvancedRobot {

    class EnemyWave {
        Point2D.Double fireLocation;
        long fireTime;
        double bulletVelocity, directAngle, distanceTraveled;
        int direction;
    }

    private static int BINS = 47;
    private static double surfStats[] = new double[BINS];
    private Point2D.Double myLocation;
    private Point2D.Double enemyLocation;

    private ArrayList enemyWaves;
    private ArrayList surfDirections;
    private ArrayList surfAbsBearings;

    private static double opponentEnergy = 100.0;

    /**
     * This is a rectangle that represents an 800x600 battle field,
     * used for a simple, iterative WallSmoothing method (by PEZ).
     * If you're not familiar with WallSmoothing, the wall stick indicates
     * the amount of space we try to always have on either end of the tank
     * (extending straight out the front or back) before touching a wall.
     */
    private static Rectangle2D.Double fieldRectangle = new Rectangle2D.Double(18, 18, 800, 600);
    private static double WALL_STICK = 400;

    public void paintRobotColors() {
        // body, gun, radar, bullet, scan
        setColors(Color.darkGray, Color.lightGray, Color.darkGray, Color.ORANGE, Color.ORANGE);
    }

    public void startUpRobot() {
        paintRobotColors();
        enemyWaves = new ArrayList();
        surfDirections = new ArrayList();
        surfAbsBearings = new ArrayList();
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
    }

    public void run() {
        startUpRobot();
        while (true) {
            turnRadarRightRadians(Double.POSITIVE_INFINITY);
        }
    }

    private Point2D.Double getMyLocation() {
        return new Point2D.Double(getX(), getY());
    }

    private double getLateralVelocity(ScannedRobotEvent e) {
        return getVelocity() * Math.sin(e.getBearingRadians());
    }

    private double getEnemyAngle(ScannedRobotEvent e) {
        return e.getBearingRadians() + getHeadingRadians();
    }

    private EnemyWave createEnemyWave (double bulletPower) {
        EnemyWave ew = new EnemyWave();
        ew.fireTime = getTime() - 1;
        ew.bulletVelocity = bulletVelocity(bulletPower);
        ew.direction = ((Integer) surfDirections.get(2)).intValue();
        ew.directAngle = ((Double) surfAbsBearings.get(2)).doubleValue();
        ew.fireLocation = (Point2D.Double) enemyLocation.clone();
        return ew;
    }

    private void adjustGun (ScannedRobotEvent e) {
        double enemyAngle = getHeadingRadians() + e.getBearingRadians();
        double gunAngle = Utils.normalRelativeAngle(enemyAngle - getGunHeadingRadians());

        setTurnGunRightRadians(gunAngle);

        setFire(2.3);        
    }

    private double getRadarAdjust (double enemyAngle) {
        return Utils.normalRelativeAngle(enemyAngle - getRadarHeadingRadians()) * 2;
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        myLocation = getMyLocation();

        double lateralVelocity = getLateralVelocity(e);
        double enemyAngle = getEnemyAngle(e);
        double radarAdjust = getRadarAdjust(enemyAngle);

        setTurnRadarRightRadians(radarAdjust);

        surfDirections.add(0, new Integer((lateralVelocity >= 0) ? 1 : -1));
        surfAbsBearings.add(0, new Double(enemyAngle + Math.PI));

        double bulletPower = opponentEnergy - e.getEnergy();

        if (bulletPower < 3.01 && bulletPower > 0.09 && surfDirections.size() > 2) {
            enemyWaves.add(createEnemyWave(bulletPower));
        }

        opponentEnergy = e.getEnergy();

        // update after EnemyWave detection, because that needs the previous
        // enemy location as the source of the wave
        enemyLocation = project(myLocation, enemyAngle, e.getDistance());

        updateWaves();
        doSurfing();
        adjustGun(e);
    }

    public void updateWaves() {
        for (int x = 0; x < enemyWaves.size(); x++) {
            EnemyWave ew = (EnemyWave) enemyWaves.get(x);

            ew.distanceTraveled = (getTime() - ew.fireTime) * ew.bulletVelocity;
            if (ew.distanceTraveled > myLocation.distance(ew.fireLocation) + 50) {
                enemyWaves.remove(x);
                x--;
            }
        }
    }

    public EnemyWave getClosestSurfableWave() {
        double closestDistance = 50000; // I juse use some very big number here
        EnemyWave surfWave = null;

        for (int x = 0; x < enemyWaves.size(); x++) {
            EnemyWave ew = (EnemyWave) enemyWaves.get(x);

            double distance = myLocation.distance(ew.fireLocation) - ew.distanceTraveled;

            if (distance > ew.bulletVelocity && distance < closestDistance) {
                surfWave = ew;
                closestDistance = distance;
            }
        }

        return surfWave;
    }

    // Given the EnemyWave that the bullet was on, and the point where we
    // were hit, calculate the index into our stat array for that factor.
    public static int getFactorIndex(EnemyWave ew, Point2D.Double targetLocation) {

        double offsetAngle = absoluteBearing(ew.fireLocation, targetLocation) - ew.directAngle;

        double factor = Utils.normalRelativeAngle(offsetAngle) / maxEscapeAngle(ew.bulletVelocity) * ew.direction;

        return (int) limit(0, (factor * ((BINS - 1) / 2)) + ((BINS - 1) / 2), BINS - 1);
    }

    // Given the EnemyWave that the bullet was on, and the point where we
    // were hit, update our stat array to reflect the danger in that area.
    public void logHit(EnemyWave ew, Point2D.Double targetLocation) {
        int index = getFactorIndex(ew, targetLocation);

        for (int x = 0; x < BINS; x++) {
            // for the spot bin that we were hit on, add 1;
            // for the bins next to it, add 1 / 2;
            // the next one, add 1 / 5; and so on...
            surfStats[x] += 1.0 / (Math.pow(index - x, 2) + 1);
        }
    }

    public void onHitByBullet(HitByBulletEvent e) {
        // If the enemyWaves collection is empty, we must have missed the
        // detection of this wave somehow.
        if (!enemyWaves.isEmpty()) {
            Point2D.Double hitBulletLocation = new Point2D.Double(e.getBullet().getX(), e.getBullet().getY());
            EnemyWave hitWave = null;

            // look through the EnemyWaves, and find one that could've hit us.
            for (int x = 0; x < enemyWaves.size(); x++) {
                EnemyWave ew = (EnemyWave) enemyWaves.get(x);

                if (Math.abs(ew.distanceTraveled - myLocation.distance(ew.fireLocation)) < 50
                        && Math.abs(bulletVelocity(e.getBullet().getPower()) - ew.bulletVelocity) < 0.001) {
                    hitWave = ew;
                    break;
                }
            }

            if (hitWave != null) {
                logHit(hitWave, hitBulletLocation);
                enemyWaves.remove(enemyWaves.lastIndexOf(hitWave));
            }
        }
    }



    public Point2D.Double predictPosition(EnemyWave surfWave, int direction) {
        Point2D.Double predictedPosition = (Point2D.Double) myLocation.clone();
        double predictedVelocity = getVelocity();
        double predictedHeading = getHeadingRadians();
        double maxTurning, moveAngle, moveDir;

        int counter = 0;
        boolean intercepted = false;

        do {
            moveAngle =
                    wallSmoothing(predictedPosition, absoluteBearing(surfWave.fireLocation,
                            predictedPosition) + (direction * (Math.PI / 2)), direction)
                            - predictedHeading;
            moveDir = 1;

            if (Math.cos(moveAngle) < 0) {
                moveAngle += Math.PI;
                moveDir = -1;
            }

            moveAngle = Utils.normalRelativeAngle(moveAngle);

            maxTurning = Math.PI / 720d * (40d - 3d * Math.abs(predictedVelocity));
            predictedHeading = Utils.normalRelativeAngle(predictedHeading
                    + limit(-maxTurning, moveAngle, maxTurning));

            predictedVelocity +=
                    (predictedVelocity * moveDir < 0 ? 2 * moveDir : moveDir);
            predictedVelocity = limit(-8, predictedVelocity, 8);

            predictedPosition = project(predictedPosition, predictedHeading,
                    predictedVelocity);

            counter++;

            if (predictedPosition.distance(surfWave.fireLocation) <
                    surfWave.distanceTraveled + (counter * surfWave.bulletVelocity)
                            + surfWave.bulletVelocity) {
                intercepted = true;
            }
        } while (!intercepted && counter < 500);

        return predictedPosition;
    }

    public double checkDanger(EnemyWave surfWave, int direction) {
        int index = getFactorIndex(surfWave,
                predictPosition(surfWave, direction));

        return surfStats[index];
    }

    public void doSurfing() {
        EnemyWave surfWave = getClosestSurfableWave();

        if (surfWave == null) {
            return;
        }

        double dangerLeft = checkDanger(surfWave, -1);
        double dangerRight = checkDanger(surfWave, 1);

        double goAngle = absoluteBearing(surfWave.fireLocation, myLocation);
        if (dangerLeft < dangerRight) {
            goAngle = wallSmoothing(myLocation, goAngle - (Math.PI / 2), -1);
        } else {
            goAngle = wallSmoothing(myLocation, goAngle + (Math.PI / 2), 1);
        }

        setBackAsFront(this, goAngle);
    }

    public double wallSmoothing(Point2D.Double botLocation, double angle, int orientation) {
        while (!fieldRectangle.contains(project(botLocation, angle, WALL_STICK))) {
            angle += orientation * 0.05;
        }
        return angle;
    }

    public static Point2D.Double project(Point2D.Double sourceLocation, double angle, double length) {
        return new Point2D.Double(sourceLocation.x + Math.sin(angle) * length, sourceLocation.y + Math.cos(angle) * length);
    }

    public static double absoluteBearing(Point2D.Double source, Point2D.Double target) {
        return Math.atan2(target.x - source.x, target.y - source.y);
    }

    public static double limit(double min, double value, double max) {
        return Math.max(min, Math.min(value, max));
    }

    public static double bulletVelocity(double power) {
        return (20.0 - (3.0 * power));
    }

    public static double maxEscapeAngle(double velocity) {
        return Math.asin(8.0 / velocity);
    }

    private static void robotSetBack (AdvancedRobot robot, double angle) {
        if (angle < 0) {
            robot.setTurnRightRadians(Math.PI + angle);
        } else {
            robot.setTurnLeftRadians(Math.PI - angle);
        }
        robot.setBack(100);
    }

    private static void robotSetAhead (AdvancedRobot robot, double angle) {
        if (angle < 0) {
            robot.setTurnLeftRadians(-1 * angle);
        } else {
            robot.setTurnRightRadians(angle);
        }
        robot.setAhead(100);
    }

    public static void setBackAsFront(AdvancedRobot robot, double goAngle) {

        double angle = Utils.normalRelativeAngle(goAngle - robot.getHeadingRadians());

        if (Math.abs(angle) > (Math.PI / 2)) {
            robotSetBack(robot, angle);
        } else {
            robotSetAhead(robot, angle);
        }
    }
}
