package fr.julien.guerresdemars;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Version Java/Swing du mini-RTS « Guerres de Mars ».
 * Aucun moteur externe n'est nécessaire : rendu, boucle de jeu, IA et entrées
 * sont gérés par Swing et Java2D.
 */
@SuppressWarnings("serial")
final class MarsWarsPanel extends JPanel {
    private static final int WORLD_W = 2600;
    private static final int WORLD_H = 1700;
    private static final int SIDEBAR_W = 230;
    private static final int POP_CAP = 40;

    private static final Color BG = new Color(0x120806);
    private static final Color PANEL = new Color(0x2A150D);
    private static final Color PANEL_2 = new Color(0x3A1E12);
    private static final Color LINE = new Color(0x6B3620);
    private static final Color LINE_2 = new Color(0x8A4A2A);
    private static final Color INK = new Color(0xFFEEDE);
    private static final Color MUTED = new Color(0xC49A80);
    private static final Color WOOD = new Color(0x7AD86A);
    private static final Color METAL = new Color(0xD6C2A8);
    private static final Color DANGER = new Color(0xFF5A52);
    private static final Color GOLD = new Color(0xFFC861);
    private static final Color HEAL = new Color(0x8BE86A);

    private static final Cost START_RES = new Cost(220, 140);
    private static final Cost WORKER_COST = new Cost(50, 0);
    private static final Cost WARRIOR_COST = new Cost(20, 30);
    private static final int WORKER_CARRY = 10;
    private static final double WORKER_MINE = 4.4;
    private static final double REPAIR_RATE = 70.0;
    private static final double REPAIR_WOOD_PER_HP = 0.12;
    private static final int UPGRADE_ATK = 7;
    private static final int UPGRADE_HP = 26;

    private final SpriteAssets sprites = new SpriteAssets();
    private final Timer timer;
    private final Set<Integer> keysDown = new HashSet<>();
    private final List<UiButton> uiButtons = new ArrayList<>();
    private final Random random = new Random();

    private Screen screen = Screen.MENU;
    private Game game;
    private Faction chosenFaction = Faction.ATLANTE;
    private MapPreset chosenPreset = MapPreset.RANDOM;
    private boolean playerWon;
    private long lastTickNanos = System.nanoTime();

    private double cameraX;
    private double cameraY;
    private double zoom = 1.0;

    private Point2D.Double dragStartScreen;
    private Point2D.Double dragCurrentScreen;
    private boolean additiveSelection;
    private Point2D.Double mouseWorld = new Point2D.Double();

    private Rectangle minimapRect = new Rectangle();
    private String toast = "";
    private double toastRemaining;

    MarsWarsPanel() {
        setPreferredSize(new Dimension(1180, 760));
        setBackground(BG);
        setFocusable(true);
        setDoubleBuffered(true);

        installInput();
        timer = new Timer(16, event -> tick());
        timer.setCoalesce(true);
        timer.start();
    }

    private void installInput() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                keysDown.add(event.getKeyCode());
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE && game != null) {
                    game.placing = null;
                }
            }

            @Override
            public void keyReleased(KeyEvent event) {
                keysDown.remove(event.getKeyCode());
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                if (handleUiClick(event.getPoint())) {
                    return;
                }
                if (screen != Screen.PLAYING || game == null || game.over) {
                    return;
                }
                int stageWidth = stageWidth();
                if (event.getX() >= stageWidth) {
                    if (minimapRect.contains(event.getPoint())) {
                        moveCameraFromMinimap(event.getX(), event.getY());
                    }
                    return;
                }

                Point2D.Double world = screenToWorld(event.getX(), event.getY());
                mouseWorld = world;
                if (event.getButton() == MouseEvent.BUTTON1) {
                    if (game.placing != null) {
                        placeBuilding(world);
                        return;
                    }
                    dragStartScreen = new Point2D.Double(event.getX(), event.getY());
                    dragCurrentScreen = new Point2D.Double(event.getX(), event.getY());
                    additiveSelection = event.isShiftDown();
                } else if (event.getButton() == MouseEvent.BUTTON3) {
                    issueOrder(world);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (screen != Screen.PLAYING || game == null || dragStartScreen == null
                        || event.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                dragCurrentScreen = new Point2D.Double(event.getX(), event.getY());
                finishSelection();
                dragStartScreen = null;
                dragCurrentScreen = null;
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                if (screen == Screen.PLAYING && event.getX() < stageWidth()) {
                    mouseWorld = screenToWorld(event.getX(), event.getY());
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (screen == Screen.PLAYING && game != null) {
                    if (event.getX() < stageWidth()) {
                        mouseWorld = screenToWorld(event.getX(), event.getY());
                    }
                    if (dragStartScreen != null) {
                        dragCurrentScreen = new Point2D.Double(event.getX(), event.getY());
                    }
                }
            }
        });

        addMouseWheelListener(this::onMouseWheel);
    }

    private void onMouseWheel(MouseWheelEvent event) {
        if (screen != Screen.PLAYING || game == null || event.getX() >= stageWidth()) {
            return;
        }
        Point2D.Double before = screenToWorld(event.getX(), event.getY());
        zoom = clamp(zoom * (event.getWheelRotation() < 0 ? 1.12 : 0.89), 0.5, 1.8);
        cameraX = before.x - event.getX() / zoom;
        cameraY = before.y - event.getY() / zoom;
        clampCamera();
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min((now - lastTickNanos) / 1_000_000_000.0, 0.05);
        lastTickNanos = now;

        if (toastRemaining > 0) {
            toastRemaining -= dt;
        }
        if (screen == Screen.PLAYING && game != null) {
            if (!game.over) {
                updateCamera(dt);
                updateGame(dt);
                runAi(dt);
            }
            updateEffects(dt);
        }
        repaint();
    }

    private void updateCamera(double dt) {
        double speed = 620.0 * dt;
        if (isKey(KeyEvent.VK_LEFT) || isKey(KeyEvent.VK_Q) || isKey(KeyEvent.VK_A)) cameraX -= speed;
        if (isKey(KeyEvent.VK_RIGHT) || isKey(KeyEvent.VK_D)) cameraX += speed;
        if (isKey(KeyEvent.VK_UP) || isKey(KeyEvent.VK_Z) || isKey(KeyEvent.VK_W)) cameraY -= speed;
        if (isKey(KeyEvent.VK_DOWN) || isKey(KeyEvent.VK_S)) cameraY += speed;
        clampCamera();
    }

    private boolean isKey(int key) {
        return keysDown.contains(key);
    }

    private void startGame(Faction playerFaction) {
        chosenFaction = playerFaction;
        Faction aiFaction = playerFaction == Faction.ATLANTE ? Faction.MARTIEN : Faction.ATLANTE;
        MapPreset resolved = chosenPreset == MapPreset.RANDOM
                ? List.of(MapPreset.PLAINES, MapPreset.ROCHERS, MapPreset.CRATERE).get(random.nextInt(3))
                : chosenPreset;

        game = new Game(playerFaction, aiFaction, resolved);
        MapBuild map = buildMap(random.nextLong(), resolved);
        game.terrain = map.terrain;
        game.nodes.addAll(map.nodes);

        Building playerBase = makeBuilding(playerFaction, BuildingKind.BASE, 260, WORLD_H / 2.0, Owner.PLAYER, true);
        Building aiBase = makeBuilding(aiFaction, BuildingKind.BASE, WORLD_W - 260, WORLD_H / 2.0, Owner.AI, true);
        game.entities.add(playerBase);
        game.entities.add(aiBase);

        for (int i = 0; i < 3; i++) {
            game.entities.add(makeUnit(playerFaction, UnitKind.WORKER,
                    playerBase.x + 72 + i * 26, playerBase.y - 40 + i * 30, Owner.PLAYER));
            game.entities.add(makeUnit(aiFaction, UnitKind.WORKER,
                    aiBase.x - 72 - i * 26, aiBase.y - 40 + i * 30, Owner.AI));
        }

        screen = Screen.PLAYING;
        zoom = getWidth() < 900 ? 0.75 : 1.0;
        cameraX = playerBase.x - visibleWorldWidth() / 2.0;
        cameraY = playerBase.y - visibleWorldHeight() / 2.0;
        clampCamera();
        showToast("Carte : " + resolved.label + " · face aux " + aiFaction.displayName);
    }

    private void updateGame(double dt) {
        for (Entity entity : new ArrayList<>(game.entities)) {
            if (entity instanceof Building building) {
                updateBuilding(building, dt);
            } else if (entity instanceof Unit unit) {
                updateUnit(unit, dt);
            }
        }

        separateUnits();

        Iterator<Entity> iterator = game.entities.iterator();
        while (iterator.hasNext()) {
            Entity dead = iterator.next();
            if (dead.hp > 0) continue;
            game.selection.remove(dead.id);
            burst(dead.x, dead.y, dead instanceof Building ? 26 : 10, dead.faction.color);
            if (dead instanceof Building building && building.kind == BuildingKind.BASE) {
                endGame(dead.owner == Owner.AI);
            }
            iterator.remove();
        }
        game.nodes.removeIf(node -> node.amount <= 0);
    }

    private void updateBuilding(Building building, double dt) {
        if (building.repairGlow > 0) building.repairGlow -= dt;
        if (building.progress < 1.0) return;

        if (building.kind == BuildingKind.TOWER) {
            building.fireCooldown -= dt;
            if (building.fireCooldown <= 0) {
                Unit target = towerTarget(building);
                if (target != null) {
                    target.hp -= 23;
                    addShot(building.x, building.y - building.r * 0.8,
                            target.x, target.y, building.faction.shotColor,
                            building.faction == Faction.MARTIEN ? ShotKind.FIRE : ShotKind.BOLT, 1.15);
                    building.fireCooldown = 0.85;
                    if (target.kind != UnitKind.WORKER && target.attackTarget == null && target.moveTarget == null) {
                        target.attackTarget = building;
                    }
                }
            }
            return;
        }

        if (!building.queue.isEmpty()) {
            building.productionCooldown -= dt;
            if (building.productionCooldown <= 0) {
                UnitKind kind = building.queue.removeFirst();
                spawnFromBuilding(building, kind);
                building.productionCooldown = building.queue.isEmpty()
                        ? 0
                        : productionTime(building.queue.peekFirst());
            }
        }
    }

    private Unit towerTarget(Building tower) {
        Unit best = null;
        double bestDistance = 235;
        for (Entity entity : game.entities) {
            if (!(entity instanceof Unit unit) || unit.owner == tower.owner) continue;
            double distance = distance(tower, unit);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = unit;
            }
        }
        return best;
    }

    private void spawnFromBuilding(Building building, UnitKind kind) {
        double angle = random.nextDouble() * Math.PI * 2;
        Unit unit = makeUnit(building.faction, kind,
                building.x + Math.cos(angle) * (building.r + 22),
                building.y + Math.sin(angle) * (building.r + 22), building.owner);
        if (building.rally != null) {
            if (kind == UnitKind.WORKER) {
                ResourceNode node = nearestNode(unit, null);
                if (node != null) commandHarvest(unit, node);
                else commandMove(unit, building.rally.x, building.rally.y);
            } else {
                commandMove(unit, building.rally.x, building.rally.y);
            }
        }
        game.entities.add(unit);
    }

    private void updateUnit(Unit unit, double dt) {
        if (unit.attackCooldownRemaining > 0) unit.attackCooldownRemaining -= dt;
        if (unit.harvestCooldown > 0) unit.harvestCooldown -= dt;
        if (unit.attackAnimation > 0) unit.attackAnimation -= dt;
        unit.moving = false;

        if (unit.buildTask != null) {
            Building target = unit.buildTask;
            if (!game.entities.contains(target)) {
                unit.buildTask = null;
                return;
            }
            if (distance(unit, target) > target.r + unit.r + 2) {
                stepTo(unit, target.x, target.y, dt);
            } else {
                target.progress = Math.min(1.0, target.progress + (target.buildSpeed * dt) / target.maxHp);
                target.hp = Math.min(target.maxHp, target.hp + target.buildSpeed * dt);
                if (target.progress >= 1.0) {
                    unit.buildTask = null;
                    if (unit.owner == Owner.PLAYER) showToast(target.kind.displayName + " terminée");
                }
            }
            return;
        }

        if (unit.repairTask != null) {
            Building target = unit.repairTask;
            if (!game.entities.contains(target) || target.hp >= target.maxHp || target.progress < 1) {
                unit.repairTask = null;
                return;
            }
            if (distance(unit, target) > target.r + unit.r + 2) {
                stepTo(unit, target.x, target.y, dt);
            } else {
                ResourceStock stock = game.resources.get(unit.owner);
                if (stock.wood > 0) {
                    double wanted = REPAIR_RATE * dt;
                    double cost = wanted * REPAIR_WOOD_PER_HP;
                    double spent = Math.min(cost, stock.wood);
                    target.hp = Math.min(target.maxHp, target.hp + spent / REPAIR_WOOD_PER_HP);
                    stock.wood -= spent;
                    target.repairGlow = 0.25;
                }
            }
            return;
        }

        if (unit.attackTarget != null) {
            Entity target = unit.attackTarget;
            if (!game.entities.contains(target) || target.hp <= 0) {
                unit.attackTarget = null;
            } else {
                double d = distance(unit, target);
                if (d <= unit.range + target.r) {
                    unit.face = target.x < unit.x ? -1 : 1;
                    if (unit.attackCooldownRemaining <= 0) {
                        dealDamage(unit, target);
                        unit.attackCooldownRemaining = unit.attackCooldown;
                        unit.attackAnimation = unit.ranged ? 0.30 : 0.38;
                    }
                } else {
                    stepTo(unit, target.x, target.y, dt);
                }
                return;
            }
        }

        if (unit.harvestNode != null) {
            ResourceNode node = unit.harvestNode;
            if (node.amount <= 0 || !game.nodes.contains(node)) {
                unit.harvestNode = nearestNode(unit, node.type);
                if (unit.harvestNode == null) unit.harvestNode = nearestNode(unit, null);
                return;
            }

            if (unit.carrying >= WORKER_CARRY) {
                Building base = nearestBase(unit);
                if (base == null) return;
                if (distance(unit, base) <= base.r + unit.r + 4) {
                    depositResources(unit);
                } else {
                    stepTo(unit, base.x, base.y, dt);
                }
            } else if (distance(unit.x, unit.y, node.x, node.y) <= node.r + unit.r + 4) {
                unit.face = node.x < unit.x ? -1 : 1;
                if (unit.harvestCooldown <= 0) {
                    double taken = Math.min(Math.min(WORKER_MINE, node.amount), WORKER_CARRY - unit.carrying);
                    node.amount -= taken;
                    unit.carrying += taken;
                    unit.carryType = node.type;
                    unit.harvestCooldown = 0.25;
                    unit.attackAnimation = 0.20;
                    spark(node.x, node.y, node.type == ResourceType.METAL ? METAL : WOOD);
                }
            } else {
                stepTo(unit, node.x, node.y, dt);
            }
            return;
        }

        if (unit.moveTarget != null) {
            if (distance(unit.x, unit.y, unit.moveTarget.x, unit.moveTarget.y) < 4) {
                unit.moveTarget = null;
            } else {
                stepTo(unit, unit.moveTarget.x, unit.moveTarget.y, dt);
            }
            return;
        }

        if (unit.kind == UnitKind.WARRIOR) {
            Entity enemy = nearestEnemy(unit, unit.ranged ? unit.range + 30 : 150);
            if (enemy != null) unit.attackTarget = enemy;
        }
    }

    private void stepTo(Unit unit, double targetX, double targetY, double dt) {
        double dx = targetX - unit.x;
        double dy = targetY - unit.y;
        double length = Math.max(0.0001, Math.hypot(dx, dy));
        double step = Math.min(unit.speed * dt, length);
        unit.x = clamp(unit.x + dx / length * step, 10, WORLD_W - 10);
        unit.y = clamp(unit.y + dy / length * step, 10, WORLD_H - 10);
        unit.moving = true;
        unit.animationTime += dt;
        if (Math.abs(dx) > 2) unit.face = dx < 0 ? -1 : 1;
        if (random.nextDouble() < 0.06) {
            dust(unit.x, unit.y + unit.r * 0.8,
                    unit.faction == Faction.MARTIEN ? unit.faction.color : new Color(0xC98B5E));
        }
    }

    private void separateUnits() {
        List<Unit> units = game.entities.stream()
                .filter(Unit.class::isInstance)
                .map(Unit.class::cast)
                .toList();
        for (int i = 0; i < units.size(); i++) {
            Unit a = units.get(i);
            for (int j = i + 1; j < units.size(); j++) {
                Unit b = units.get(j);
                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double d = Math.hypot(dx, dy);
                double min = a.r + b.r - 2;
                if (d > 0 && d < min) {
                    double push = (min - d) / 2.0 / d;
                    a.x -= dx * push;
                    a.y -= dy * push;
                    b.x += dx * push;
                    b.y += dy * push;
                }
            }
        }
    }

    private void dealDamage(Unit attacker, Entity target) {
        target.hp -= attacker.damage;
        if (attacker.ranged) {
            addShot(attacker.x, attacker.y - attacker.r * 0.6,
                    target.x, target.y, attacker.faction.shotColor,
                    attacker.faction == Faction.MARTIEN ? ShotKind.FIRE : ShotKind.BOLT, 1.0);
        } else {
            spark(target.x, target.y, new Color(0xFFD9A0));
        }
        if (target instanceof Unit unit && unit.kind != UnitKind.WORKER
                && unit.attackTarget == null && unit.moveTarget == null) {
            unit.attackTarget = attacker;
        }
    }

    private void depositResources(Unit unit) {
        ResourceType type = unit.carryType == null ? ResourceType.WOOD : unit.carryType;
        ResourceStock stock = game.resources.get(unit.owner);
        if (type == ResourceType.WOOD) stock.wood += unit.carrying;
        else stock.metal += unit.carrying;
        unit.carrying = 0;
    }

    private void runAi(double dt) {
        game.aiDecisionTimer -= dt;
        game.aiAttackTimer -= dt;
        game.aiBuildTimer -= dt;

        List<Entity> own = game.entities.stream().filter(entity -> entity.owner == Owner.AI).toList();
        Building base = own.stream().filter(Building.class::isInstance).map(Building.class::cast)
                .filter(building -> building.kind == BuildingKind.BASE).findFirst().orElse(null);
        if (base == null) return;

        List<Unit> workers = own.stream().filter(Unit.class::isInstance).map(Unit.class::cast)
                .filter(unit -> unit.kind == UnitKind.WORKER).toList();
        List<Unit> warriors = own.stream().filter(Unit.class::isInstance).map(Unit.class::cast)
                .filter(unit -> unit.kind == UnitKind.WARRIOR).toList();
        List<Building> barracks = own.stream().filter(Building.class::isInstance).map(Building.class::cast)
                .filter(building -> building.kind == BuildingKind.BARRACKS).toList();
        Building forge = own.stream().filter(Building.class::isInstance).map(Building.class::cast)
                .filter(building -> building.kind == BuildingKind.FORGE).findFirst().orElse(null);
        List<Building> towers = own.stream().filter(Building.class::isInstance).map(Building.class::cast)
                .filter(building -> building.kind == BuildingKind.TOWER).toList();

        for (int i = 0; i < workers.size(); i++) {
            Unit worker = workers.get(i);
            if (worker.harvestNode == null && worker.moveTarget == null && worker.carrying == 0
                    && worker.buildTask == null && worker.repairTask == null) {
                ResourceType wanted = i % 2 == 0 ? ResourceType.WOOD : ResourceType.METAL;
                ResourceNode node = nearestNode(worker, wanted);
                if (node == null) node = nearestNode(worker, null);
                if (node != null) commandHarvest(worker, node);
            }
        }

        if (game.aiDecisionTimer <= 0) {
            game.aiDecisionTimer = 1.4;
            if (workers.size() < 7 && base.queue.size() < 2 && canAfford(Owner.AI, WORKER_COST)) {
                spend(Owner.AI, WORKER_COST);
                queueUnit(base, UnitKind.WORKER);
            } else if (barracks.isEmpty() && workers.size() >= 3 && canAfford(Owner.AI, BuildingKind.BARRACKS.cost)) {
                spend(Owner.AI, BuildingKind.BARRACKS.cost);
                game.entities.add(makeBuilding(game.aiFaction, BuildingKind.BARRACKS,
                        base.x - 130, base.y + 90, Owner.AI, true));
            } else if (!barracks.isEmpty() && canAfford(Owner.AI, WARRIOR_COST)
                    && barracks.get(0).queue.size() < 3 && population(Owner.AI) < POP_CAP) {
                spend(Owner.AI, WARRIOR_COST);
                queueUnit(barracks.get(0), UnitKind.WARRIOR);
            }
        }

        if (game.aiBuildTimer <= 0) {
            game.aiBuildTimer = 10;
            if (forge == null && warriors.size() >= 3 && canAfford(Owner.AI, BuildingKind.FORGE.cost)) {
                spend(Owner.AI, BuildingKind.FORGE.cost);
                game.entities.add(makeBuilding(game.aiFaction, BuildingKind.FORGE,
                        base.x - 110, base.y - 115, Owner.AI, true));
            } else if (forge != null && game.upgrades.get(Owner.AI).level < 3
                    && canAfford(Owner.AI, upgradeCost(Owner.AI))) {
                spend(Owner.AI, upgradeCost(Owner.AI));
                applyUpgrade(Owner.AI);
            } else if (towers.size() < 2 && canAfford(Owner.AI, BuildingKind.TOWER.cost)) {
                spend(Owner.AI, BuildingKind.TOWER.cost);
                double side = towers.isEmpty() ? 1 : -1;
                game.entities.add(makeBuilding(game.aiFaction, BuildingKind.TOWER,
                        base.x + 95, base.y + side * 150, Owner.AI, true));
            }
        }

        if (game.aiAttackTimer <= 0 && warriors.size() >= 6) {
            game.aiAttackTimer = 16;
            Entity target = game.entities.stream()
                    .filter(entity -> entity.owner == Owner.PLAYER && entity instanceof Building b && b.kind == BuildingKind.BASE)
                    .findFirst()
                    .orElseGet(() -> game.entities.stream().filter(entity -> entity.owner == Owner.PLAYER).findFirst().orElse(null));
            if (target != null) warriors.forEach(unit -> commandAttack(unit, target));
        }
    }

    private void applyUpgrade(Owner owner) {
        Upgrade upgrade = game.upgrades.get(owner);
        upgrade.level++;
        upgrade.attackBonus += UPGRADE_ATK;
        upgrade.hpBonus += UPGRADE_HP;
        for (Entity entity : game.entities) {
            if (entity.owner == owner && entity instanceof Unit unit && unit.kind == UnitKind.WARRIOR) {
                unit.maxHp += UPGRADE_HP;
                unit.hp += UPGRADE_HP;
                unit.damage += UPGRADE_ATK;
            }
        }
        if (owner == Owner.PLAYER) showToast("Troupes améliorées · niveau " + upgrade.level);
    }

    private void updateEffects(double dt) {
        if (game == null) return;
        Iterator<Shot> shotIterator = game.shots.iterator();
        while (shotIterator.hasNext()) {
            Shot shot = shotIterator.next();
            shot.time += dt;
            if (shot.time >= shot.duration) {
                burstImpact(shot);
                shotIterator.remove();
            }
        }

        Iterator<Particle> particleIterator = game.particles.iterator();
        while (particleIterator.hasNext()) {
            Particle particle = particleIterator.next();
            particle.life -= dt;
            if (particle.life <= 0) {
                particleIterator.remove();
            } else {
                particle.x += particle.vx * dt;
                particle.y += particle.vy * dt;
                particle.vy += 60 * dt;
            }
        }

        Iterator<Ping> pingIterator = game.pings.iterator();
        while (pingIterator.hasNext()) {
            Ping ping = pingIterator.next();
            ping.life -= dt;
            if (ping.life <= 0) pingIterator.remove();
        }
    }

    private void addShot(double x, double y, double targetX, double targetY,
                         Color color, ShotKind kind, double scale) {
        double duration = Math.max(0.08, distance(x, y, targetX, targetY) / 650.0);
        game.shots.add(new Shot(x, y, targetX, targetY, color, kind, scale, duration));
    }

    private void spark(double x, double y, Color color) {
        for (int i = 0; i < 6; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double speed = 30 + random.nextDouble() * 70;
            game.particles.add(new Particle(x, y, Math.cos(angle) * speed,
                    Math.sin(angle) * speed - 20, 0.35, color, 2.2));
        }
    }

    private void burst(double x, double y, int amount, Color color) {
        for (int i = 0; i < amount; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double speed = 40 + random.nextDouble() * 130;
            game.particles.add(new Particle(x, y, Math.cos(angle) * speed,
                    Math.sin(angle) * speed - 25, 0.55 + random.nextDouble() * 0.3,
                    color, 2.3 + random.nextDouble() * 2.0));
        }
    }

    private void dust(double x, double y, Color color) {
        game.particles.add(new Particle(x + random.nextDouble() * 8 - 4, y,
                random.nextDouble() * 16 - 8, -6 - random.nextDouble() * 8,
                0.35, color, 2.0));
    }

    private void burstImpact(Shot shot) {
        int amount = shot.kind == ShotKind.FIRE ? 9 : 5;
        burst(shot.targetX, shot.targetY, amount, shot.color);
    }

    private void finishSelection() {
        if (game == null || dragStartScreen == null || dragCurrentScreen == null) return;
        if (!additiveSelection) game.selection.clear();
        double dx = dragCurrentScreen.x - dragStartScreen.x;
        double dy = dragCurrentScreen.y - dragStartScreen.y;
        boolean dragged = Math.hypot(dx, dy) > 6;

        if (dragged) {
            Point2D.Double a = screenToWorld(Math.min(dragStartScreen.x, dragCurrentScreen.x),
                    Math.min(dragStartScreen.y, dragCurrentScreen.y));
            Point2D.Double b = screenToWorld(Math.max(dragStartScreen.x, dragCurrentScreen.x),
                    Math.max(dragStartScreen.y, dragCurrentScreen.y));
            boolean gotUnit = false;
            for (Entity entity : game.entities) {
                if (entity.owner == Owner.PLAYER && entity instanceof Unit
                        && entity.x >= a.x && entity.x <= b.x && entity.y >= a.y && entity.y <= b.y) {
                    game.selection.add(entity.id);
                    gotUnit = true;
                }
            }
            if (!gotUnit) {
                for (Entity entity : game.entities) {
                    if (entity.owner == Owner.PLAYER && entity instanceof Building
                            && entity.x >= a.x && entity.x <= b.x && entity.y >= a.y && entity.y <= b.y) {
                        game.selection.add(entity.id);
                    }
                }
            }
        } else {
            Point2D.Double world = screenToWorld(dragStartScreen.x, dragStartScreen.y);
            Entity entity = entityAt(world.x, world.y, 0);
            if (entity != null && entity.owner == Owner.PLAYER) game.selection.add(entity.id);
        }
    }

    private void issueOrder(Point2D.Double point) {
        List<Entity> selected = selectedOwn();
        if (selected.isEmpty()) return;

        for (Entity entity : selected) {
            if (entity instanceof Building building) {
                building.rally = new Point2D.Double(point.x, point.y);
            }
        }

        List<Unit> units = selected.stream().filter(Unit.class::isInstance).map(Unit.class::cast).toList();
        if (units.isEmpty()) return;

        Entity clickedEntity = entityAt(point.x, point.y, 0);
        ResourceNode node = nodeAt(point.x, point.y);
        if (clickedEntity != null && clickedEntity.owner == Owner.AI) {
            units.forEach(unit -> commandAttack(unit, clickedEntity));
            markPing(point, DANGER);
            return;
        }

        if (clickedEntity instanceof Building building && building.owner == Owner.PLAYER
                && building.progress >= 1 && building.hp < building.maxHp) {
            boolean hasWorker = false;
            for (Unit unit : units) {
                if (unit.kind == UnitKind.WORKER) {
                    commandRepair(unit, building);
                    hasWorker = true;
                } else {
                    commandMove(unit, point.x, point.y);
                }
            }
            if (hasWorker) {
                markPing(point, HEAL);
                showToast("Réparation en cours");
                return;
            }
        }

        if (node != null) {
            for (Unit unit : units) {
                if (unit.kind == UnitKind.WORKER) commandHarvest(unit, node);
                else commandMove(unit, point.x, point.y);
            }
            markPing(point, node.type == ResourceType.METAL ? METAL : WOOD);
            return;
        }

        int columns = (int) Math.ceil(Math.sqrt(units.size()));
        for (int i = 0; i < units.size(); i++) {
            double offsetX = (i % columns - columns / 2.0) * 28;
            double offsetY = (i / columns - columns / 2.0) * 28;
            commandMove(units.get(i), point.x + offsetX, point.y + offsetY);
        }
        markPing(point, GOLD);
    }

    private void commandMove(Unit unit, double x, double y) {
        unit.moveTarget = new Point2D.Double(x, y);
        unit.attackTarget = null;
        unit.harvestNode = null;
        unit.buildTask = null;
        unit.repairTask = null;
    }

    private void commandAttack(Unit unit, Entity target) {
        unit.attackTarget = target;
        unit.moveTarget = null;
        unit.harvestNode = null;
        unit.buildTask = null;
        unit.repairTask = null;
    }

    private void commandHarvest(Unit unit, ResourceNode node) {
        unit.harvestNode = node;
        unit.moveTarget = null;
        unit.attackTarget = null;
        unit.buildTask = null;
        unit.repairTask = null;
    }

    private void commandRepair(Unit unit, Building building) {
        unit.repairTask = building;
        unit.moveTarget = null;
        unit.attackTarget = null;
        unit.harvestNode = null;
        unit.buildTask = null;
    }

    private void markPing(Point2D.Double point, Color color) {
        game.pings.add(new Ping(point.x, point.y, 0.5, color));
    }

    private void placeBuilding(Point2D.Double point) {
        BuildingKind kind = game.placing;
        if (kind == null) return;
        if (!canAfford(Owner.PLAYER, kind.cost)) {
            showToast("Ressources insuffisantes");
            game.placing = null;
            return;
        }
        if (!canPlace(point.x, point.y, kind)) {
            showToast("Emplacement invalide");
            return;
        }

        spend(Owner.PLAYER, kind.cost);
        Building building = makeBuilding(game.playerFaction, kind, point.x, point.y, Owner.PLAYER, false);
        game.entities.add(building);
        selectedOwn().stream().filter(Unit.class::isInstance).map(Unit.class::cast)
                .filter(unit -> unit.kind == UnitKind.WORKER)
                .findFirst()
                .ifPresent(worker -> {
                    worker.buildTask = building;
                    worker.moveTarget = null;
                    worker.harvestNode = null;
                    worker.attackTarget = null;
                    worker.repairTask = null;
                });
        game.placing = null;
    }

    private boolean canPlace(double x, double y, BuildingKind kind) {
        if (x < 40 || y < 40 || x > WORLD_W - 40 || y > WORLD_H - 40) return false;
        for (Entity entity : game.entities) {
            if (entity instanceof Building && distance(x, y, entity.x, entity.y) < entity.r + kind.radius + 8) {
                return false;
            }
        }
        for (ResourceNode node : game.nodes) {
            if (distance(x, y, node.x, node.y) < node.r + kind.radius + 6) return false;
        }
        return true;
    }

    private void endGame(boolean won) {
        if (game == null || game.over) return;
        game.over = true;
        playerWon = won;
        screen = Screen.END;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        uiButtons.clear();

        if (screen == Screen.MENU) {
            drawMenu(g);
        } else if (screen == Screen.PLAYING) {
            drawGame(g);
        } else {
            drawEnd(g);
        }
        g.dispose();
    }

    private void drawMenu(Graphics2D g) {
        int width = getWidth();
        int height = getHeight();
        g.setPaint(new GradientPaint(0, 0, new Color(0x7A3A1E), 0, height, new Color(0x160A06)));
        g.fillRect(0, 0, width, height);

        drawCenteredText(g, "GUERRES DE MARS", width / 2, 82,
                new Font("SansSerif", Font.BOLD, 48), new Color(0xFFB270));
        drawCenteredText(g, "ATLANTES CONTRE MARTIENS", width / 2, 116,
                new Font("SansSerif", Font.BOLD, 16), MUTED);
        drawCenteredText(g, "Choisis ta civilisation puis détruis la cité ennemie.", width / 2, 150,
                new Font("SansSerif", Font.PLAIN, 15), INK);

        int cardWidth = Math.min(390, (width - 80) / 2);
        int cardHeight = Math.min(370, height - 300);
        int gap = 24;
        int total = cardWidth * 2 + gap;
        int startX = (width - total) / 2;
        int top = 180;
        drawFactionCard(g, new Rectangle(startX, top, cardWidth, cardHeight), Faction.ATLANTE);
        drawFactionCard(g, new Rectangle(startX + cardWidth + gap, top, cardWidth, cardHeight), Faction.MARTIEN);

        drawCenteredText(g, "CARTE", width / 2, top + cardHeight + 38,
                new Font("SansSerif", Font.BOLD, 11), MUTED);
        int presetY = top + cardHeight + 52;
        int presetW = 142;
        int presetGap = 8;
        MapPreset[] presets = MapPreset.values();
        int presetsTotal = presets.length * presetW + (presets.length - 1) * presetGap;
        int presetX = (width - presetsTotal) / 2;
        for (MapPreset preset : presets) {
            Rectangle rect = new Rectangle(presetX, presetY, presetW, 34);
            boolean active = chosenPreset == preset;
            drawButton(g, rect, preset.label, active ? GOLD : INK, active ? new Color(0x4A2D13) : PANEL,
                    () -> true, () -> chosenPreset = preset);
            presetX += presetW + presetGap;
        }
    }

    private void drawFactionCard(Graphics2D g, Rectangle rect, Faction faction) {
        boolean active = chosenFaction == faction;
        g.setColor(active ? new Color(0x442515) : new Color(0x2B170F));
        g.fill(new RoundRectangle2D.Double(rect.x, rect.y, rect.width, rect.height, 22, 22));
        g.setStroke(new BasicStroke(active ? 3f : 2f));
        g.setColor(active ? faction.color : LINE_2);
        g.draw(new RoundRectangle2D.Double(rect.x, rect.y, rect.width, rect.height, 22, 22));

        BufferedImage image = sprites.get(faction == Faction.ATLANTE ? "atl_idle" : "mar_atk");
        if (image != null) {
            int imageHeight = Math.min(170, rect.height / 2);
            int imageWidth = (int) Math.round(imageHeight * image.getWidth() / (double) image.getHeight());
            g.drawImage(image, rect.x + rect.width / 2 - imageWidth / 2, rect.y + 14,
                    imageWidth, imageHeight, null);
        }

        drawCenteredText(g, faction.displayName, rect.x + rect.width / 2, rect.y + 205,
                new Font("SansSerif", Font.BOLD, 24), faction.color);
        drawCenteredText(g, faction.title, rect.x + rect.width / 2, rect.y + 228,
                new Font("SansSerif", Font.BOLD, 11), MUTED);

        drawWrappedText(g, faction.description, rect.x + 22, rect.y + 250, rect.width - 44,
                new Font("SansSerif", Font.PLAIN, 13), INK, 18);

        String stat = faction == Faction.ATLANTE
                ? "VIE 170   ATK 26   PORTÉE 34"
                : "VIE 105   ATK 19   PORTÉE 178";
        drawCenteredText(g, stat, rect.x + rect.width / 2, rect.y + rect.height - 48,
                new Font("Monospaced", Font.BOLD, 12), faction.color);

        Rectangle play = new Rectangle(rect.x + 34, rect.y + rect.height - 36, rect.width - 68, 28);
        drawButton(g, play, "JOUER LES " + faction.displayName.toUpperCase(), INK,
                active ? shade(faction.color, -115) : PANEL_2, () -> true, () -> startGame(faction));

        uiButtons.add(new UiButton(rect, () -> true, () -> startGame(faction)));
    }

    private void drawGame(Graphics2D g) {
        int stageWidth = stageWidth();
        g.setColor(new Color(0x8A4526));
        g.fillRect(0, 0, stageWidth, getHeight());

        Shape oldClip = g.getClip();
        g.setClip(0, 0, stageWidth, getHeight());
        AffineTransform oldTransform = g.getTransform();
        g.scale(zoom, zoom);
        g.translate(-cameraX, -cameraY);

        if (game.terrain != null) g.drawImage(game.terrain, 0, 0, null);
        for (ResourceNode node : game.nodes) drawNode(g, node);

        List<Building> buildings = game.entities.stream().filter(Building.class::isInstance)
                .map(Building.class::cast).toList();
        List<Unit> units = game.entities.stream().filter(Unit.class::isInstance)
                .map(Unit.class::cast).sorted(Comparator.comparingDouble(unit -> unit.y)).toList();

        buildings.forEach(building -> drawBuilding(g, building));
        units.forEach(unit -> drawUnit(g, unit));
        game.shots.forEach(shot -> drawShot(g, shot));
        game.particles.forEach(particle -> drawParticle(g, particle));
        game.pings.forEach(ping -> drawPing(g, ping));

        if (game.placing != null) drawPlacementPreview(g, game.placing);

        g.setTransform(oldTransform);
        g.setClip(oldClip);

        drawTopHud(g, stageWidth);
        drawSidebar(g, stageWidth);
        drawSelectionBox(g);
        drawToast(g, stageWidth);
    }

    private void drawTopHud(Graphics2D g, int stageWidth) {
        g.setPaint(new GradientPaint(0, 0, new Color(18, 8, 6, 235), 0, 70, new Color(18, 8, 6, 0)));
        g.fillRect(0, 0, stageWidth, 72);
        ResourceStock stock = game.resources.get(Owner.PLAYER);
        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        g.setColor(WOOD);
        g.fillRect(14, 17, 13, 13);
        g.setColor(INK);
        g.drawString(Integer.toString((int) stock.wood), 35, 30);
        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.drawString("BOIS", 72, 29);

        g.setColor(METAL);
        g.fillRect(126, 17, 13, 13);
        g.setColor(INK);
        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        g.drawString(Integer.toString((int) stock.metal), 147, 30);
        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.drawString("MÉTAL", 187, 29);

        g.setColor(GOLD);
        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        g.drawString("⚔ " + population(Owner.PLAYER) + "/" + POP_CAP, 245, 30);

        String objective = "Objectif : détruire la Cité ennemie";
        Font font = new Font("SansSerif", Font.BOLD, 12);
        FontMetrics metrics = g.getFontMetrics(font);
        int width = metrics.stringWidth(objective) + 28;
        int x = stageWidth / 2 - width / 2;
        g.setColor(new Color(26, 13, 8, 205));
        g.fillRoundRect(x, 46, width, 25, 18, 18);
        g.setColor(LINE);
        g.drawRoundRect(x, 46, width, 25, 18, 18);
        drawCenteredText(g, objective, stageWidth / 2, 63, font, MUTED);

        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(new Color(255, 238, 222, 180));
        g.drawString("Clic G : sélectionner · Clic D : ordre · ZQSD/flèches : caméra · molette : zoom", 12, getHeight() - 14);
    }

    private void drawSidebar(Graphics2D g, int x) {
        int width = getWidth() - x;
        g.setPaint(new GradientPaint(x, 0, new Color(0x482514), x + width, getHeight(), PANEL));
        g.fillRect(x, 0, width, getHeight());
        g.setColor(LINE_2);
        g.fillRect(x, 0, 3, getHeight());

        int pad = 10;
        int innerX = x + pad;
        int innerW = width - pad * 2;
        int y = 10;

        Rectangle selectionPanel = new Rectangle(innerX, y, innerW, 158);
        drawPanel(g, selectionPanel);
        drawSelectionPanel(g, selectionPanel);
        y += selectionPanel.height + 10;

        Rectangle actionsPanel = new Rectangle(innerX, y, innerW, Math.max(220, getHeight() - y - 174));
        drawPanel(g, actionsPanel);
        drawActionsPanel(g, actionsPanel);

        int miniHeight = 126;
        Rectangle miniPanel = new Rectangle(innerX, getHeight() - miniHeight - 10, innerW, miniHeight);
        drawPanel(g, miniPanel);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.setColor(MUTED);
        g.drawString("CARTE", miniPanel.x + 8, miniPanel.y + 16);
        minimapRect = new Rectangle(miniPanel.x + 8, miniPanel.y + 23, miniPanel.width - 16, miniPanel.height - 31);
        drawMinimap(g, minimapRect);
    }

    private void drawSelectionPanel(Graphics2D g, Rectangle panel) {
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.setColor(MUTED);
        g.drawString(game.playerFaction.displayName.toUpperCase(), panel.x + 9, panel.y + 16);

        List<Entity> selected = selectedOwn();
        Entity first = selected.isEmpty() ? null : selected.get(0);
        Rectangle portrait = new Rectangle(panel.x + 9, panel.y + 27, 48, 48);
        g.setColor(BG);
        g.fillRoundRect(portrait.x, portrait.y, portrait.width, portrait.height, 8, 8);
        g.setColor(LINE_2);
        g.drawRoundRect(portrait.x, portrait.y, portrait.width, portrait.height, 8, 8);

        String name = "Rien";
        String sub = "Sélectionne une unité";
        if (first != null) {
            name = first instanceof Unit unit ? unit.kind.displayName : ((Building) first).kind.displayName;
            sub = selected.size() > 1 ? selected.size() + " éléments sélectionnés" : first.faction.displayName;
            if (first instanceof Unit unit) {
                BufferedImage image = unitSprite(unit);
                if (image != null) {
                    int h = 44;
                    int w = (int) Math.round(h * image.getWidth() / (double) image.getHeight());
                    g.drawImage(image, portrait.x + portrait.width / 2 - w / 2, portrait.y + 2, w, h, null);
                }
            } else {
                drawBuildingIcon(g, (Building) first, portrait.x + portrait.width / 2, portrait.y + portrait.height / 2, 20);
            }
        }

        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.setColor(INK);
        g.drawString(name, panel.x + 66, panel.y + 46);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(MUTED);
        g.drawString(sub, panel.x + 66, panel.y + 63);

        if (first != null) {
            double ratio = clamp(first.hp / first.maxHp, 0, 1);
            int barX = panel.x + 9;
            int barY = panel.y + 83;
            int barW = panel.width - 18;
            g.setColor(BG);
            g.fillRoundRect(barX, barY, barW, 8, 6, 6);
            g.setColor(ratio > .5 ? HEAL : ratio > .25 ? GOLD : DANGER);
            g.fillRoundRect(barX, barY, (int) Math.round(barW * ratio), 8, 6, 6);
            g.setFont(new Font("Monospaced", Font.BOLD, 10));
            g.setColor(INK);
            g.drawString((int) first.hp + " / " + (int) first.maxHp, barX, barY + 21);

            if (first instanceof Unit unit) {
                g.setColor(MUTED);
                g.drawString("ATK " + (int) unit.damage + "   PORTÉE " + (int) unit.range, barX + 74, barY + 21);
            }
        }

        int buttonY = panel.y + panel.height - 37;
        Rectangle army = new Rectangle(panel.x + 9, buttonY, (panel.width - 24) / 2, 27);
        Rectangle clear = new Rectangle(army.x + army.width + 6, buttonY, army.width, 27);
        drawButton(g, army, "⚔ Armée", INK, BG, () -> true, this::selectArmy);
        drawButton(g, clear, "✕ Vider", MUTED, BG, () -> true, () -> game.selection.clear());
    }

    private void drawActionsPanel(Graphics2D g, Rectangle panel) {
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.setColor(MUTED);
        g.drawString("ORDRES", panel.x + 9, panel.y + 16);

        List<ActionSpec> actions = buildActions();
        int y = panel.y + 25;
        for (ActionSpec action : actions) {
            Rectangle rect = new Rectangle(panel.x + 8, y, panel.width - 16, 46);
            boolean enabled = action.enabled.getAsBoolean();
            drawActionButton(g, rect, action, enabled);
            uiButtons.add(new UiButton(rect, action.enabled, action.action));
            y += 52;
            if (y > panel.y + panel.height - 44) break;
        }

        Optional<Building> queued = selectedOwn().stream().filter(Building.class::isInstance)
                .map(Building.class::cast).filter(building -> !building.queue.isEmpty()).findFirst();
        if (queued.isPresent()) {
            Building building = queued.get();
            int qY = Math.min(y + 4, panel.y + panel.height - 32);
            int qX = panel.x + 8;
            int i = 0;
            for (UnitKind ignored : building.queue) {
                if (i >= 5) break;
                g.setColor(BG);
                g.fillRoundRect(qX + i * 29, qY, 24, 24, 5, 5);
                g.setColor(LINE);
                g.drawRoundRect(qX + i * 29, qY, 24, 24, 5, 5);
                g.setColor(GOLD);
                g.drawString(i == 0 ? "▶" : "•", qX + 7 + i * 29, qY + 17);
                i++;
            }
        }
    }

    private List<ActionSpec> buildActions() {
        List<ActionSpec> actions = new ArrayList<>();
        List<Entity> selected = selectedOwn();
        Building base = selected.stream().filter(Building.class::isInstance).map(Building.class::cast)
                .filter(building -> building.kind == BuildingKind.BASE).findFirst().orElse(null);
        Building barracks = selected.stream().filter(Building.class::isInstance).map(Building.class::cast)
                .filter(building -> building.kind == BuildingKind.BARRACKS).findFirst().orElse(null);
        Building forge = selected.stream().filter(Building.class::isInstance).map(Building.class::cast)
                .filter(building -> building.kind == BuildingKind.FORGE).findFirst().orElse(null);
        boolean hasWorker = selected.stream().anyMatch(entity -> entity instanceof Unit unit && unit.kind == UnitKind.WORKER);

        if (base != null) {
            actions.add(new ActionSpec("⛏", "Serviteur", "Bois & métal", WORKER_COST,
                    () -> canAfford(Owner.PLAYER, WORKER_COST) && population(Owner.PLAYER) < POP_CAP && base.queue.size() < 5,
                    () -> {
                        spend(Owner.PLAYER, WORKER_COST);
                        queueUnit(base, UnitKind.WORKER);
                    }));
        }
        if (barracks != null) {
            String subtitle = game.playerFaction.ranged ? "Sphères d'énergie" : "Épée · mêlée";
            actions.add(new ActionSpec("⚔", "Combattant", subtitle, WARRIOR_COST,
                    () -> canAfford(Owner.PLAYER, WARRIOR_COST) && population(Owner.PLAYER) < POP_CAP && barracks.queue.size() < 5,
                    () -> {
                        spend(Owner.PLAYER, WARRIOR_COST);
                        queueUnit(barracks, UnitKind.WARRIOR);
                    }));
        }
        if (forge != null) {
            Cost upgradeCost = upgradeCost(Owner.PLAYER);
            actions.add(new ActionSpec("✦", "Améliorer", "Troupes · niv " + game.upgrades.get(Owner.PLAYER).level,
                    upgradeCost,
                    () -> canAfford(Owner.PLAYER, upgradeCost) && game.upgrades.get(Owner.PLAYER).level < 5,
                    () -> {
                        Cost current = upgradeCost(Owner.PLAYER);
                        if (canAfford(Owner.PLAYER, current)) {
                            spend(Owner.PLAYER, current);
                            applyUpgrade(Owner.PLAYER);
                        }
                    }));
        }
        if (hasWorker) {
            for (BuildingKind kind : List.of(BuildingKind.BARRACKS, BuildingKind.FORGE, BuildingKind.TOWER)) {
                actions.add(new ActionSpec(kind.icon, kind.displayName, "Construire", kind.cost,
                        () -> canAfford(Owner.PLAYER, kind.cost),
                        () -> {
                            game.placing = game.placing == kind ? null : kind;
                            if (game.placing != null) showToast("Place : " + kind.displayName);
                        }));
            }
        }
        return actions;
    }

    private void queueUnit(Building building, UnitKind kind) {
        boolean empty = building.queue.isEmpty();
        building.queue.addLast(kind);
        if (empty) building.productionCooldown = productionTime(kind);
    }

    private double productionTime(UnitKind kind) {
        return kind == UnitKind.WORKER ? 3.0 : 4.0;
    }

    private void drawActionButton(Graphics2D g, Rectangle rect, ActionSpec action, boolean enabled) {
        g.setColor(enabled ? new Color(0x482514) : new Color(0x2A1A13));
        g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 9, 9);
        g.setColor(enabled ? LINE_2 : LINE);
        g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 9, 9);
        g.setColor(enabled ? INK : new Color(140, 110, 95));
        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        g.drawString(action.icon, rect.x + 10, rect.y + 29);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString(action.label, rect.x + 38, rect.y + 19);
        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g.setColor(enabled ? MUTED : new Color(120, 90, 75));
        g.drawString(action.subtitle, rect.x + 38, rect.y + 34);
        g.setFont(new Font("Monospaced", Font.BOLD, 9));
        g.setColor(enabled ? GOLD : new Color(120, 90, 75));
        String cost = action.cost.wood + "B " + action.cost.metal + "M";
        g.drawString(cost, rect.x + rect.width - g.getFontMetrics().stringWidth(cost) - 8, rect.y + 27);
    }

    private void drawMinimap(Graphics2D g, Rectangle rect) {
        g.setColor(BG);
        g.fillRect(rect.x, rect.y, rect.width, rect.height);
        if (game.terrain != null) g.drawImage(game.terrain, rect.x, rect.y, rect.width, rect.height, null);
        double sx = rect.width / (double) WORLD_W;
        double sy = rect.height / (double) WORLD_H;
        for (ResourceNode node : game.nodes) {
            g.setColor(node.type == ResourceType.METAL ? METAL : WOOD);
            g.fillRect(rect.x + (int) (node.x * sx) - 1, rect.y + (int) (node.y * sy) - 1, 3, 3);
        }
        for (Entity entity : game.entities) {
            g.setColor(entity.owner == Owner.PLAYER ? game.playerFaction.color : DANGER);
            int size = entity instanceof Building ? 5 : 3;
            g.fillRect(rect.x + (int) (entity.x * sx) - size / 2,
                    rect.y + (int) (entity.y * sy) - size / 2, size, size);
        }
        g.setColor(Color.WHITE);
        g.drawRect(rect.x + (int) (cameraX * sx), rect.y + (int) (cameraY * sy),
                (int) (visibleWorldWidth() * sx), (int) (visibleWorldHeight() * sy));
        g.setColor(LINE_2);
        g.drawRect(rect.x, rect.y, rect.width, rect.height);
    }

    private void drawSelectionBox(Graphics2D g) {
        if (dragStartScreen == null || dragCurrentScreen == null) return;
        int x = (int) Math.min(dragStartScreen.x, dragCurrentScreen.x);
        int y = (int) Math.min(dragStartScreen.y, dragCurrentScreen.y);
        int width = (int) Math.abs(dragStartScreen.x - dragCurrentScreen.x);
        int height = (int) Math.abs(dragStartScreen.y - dragCurrentScreen.y);
        g.setColor(new Color(255, 200, 97, 38));
        g.fillRect(x, y, width, height);
        g.setColor(GOLD);
        g.drawRect(x, y, width, height);
    }

    private void drawToast(Graphics2D g, int stageWidth) {
        if (toastRemaining <= 0 || toast.isBlank()) return;
        Font font = new Font("SansSerif", Font.BOLD, 13);
        FontMetrics metrics = g.getFontMetrics(font);
        int width = metrics.stringWidth(toast) + 30;
        int x = stageWidth / 2 - width / 2;
        int y = 82;
        g.setColor(new Color(26, 13, 8, 245));
        g.fillRoundRect(x, y, width, 34, 10, 10);
        g.setColor(LINE_2);
        g.drawRoundRect(x, y, width, 34, 10, 10);
        drawCenteredText(g, toast, stageWidth / 2, y + 22, font, INK);
    }

    private void drawPlacementPreview(Graphics2D g, BuildingKind kind) {
        boolean valid = canPlace(mouseWorld.x, mouseWorld.y, kind);
        CompositeState state = new CompositeState(g);
        g.setColor(new Color(kind == BuildingKind.TOWER ? 255 : 120, 200, 97, 110));
        drawBuildingShape(g, kind, game.playerFaction, mouseWorld.x, mouseWorld.y, kind.radius, false, 1.0);
        g.setColor(valid ? HEAL : DANGER);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Ellipse2D.Double(mouseWorld.x - kind.radius - 4, mouseWorld.y - kind.radius - 4,
                (kind.radius + 4) * 2, (kind.radius + 4) * 2));
        if (kind == BuildingKind.TOWER) {
            g.setColor(new Color(255, 200, 97, 80));
            g.draw(new Ellipse2D.Double(mouseWorld.x - 235, mouseWorld.y - 235, 470, 470));
        }
        state.restore(g);
    }

    private void drawBuilding(Graphics2D g, Building building) {
        boolean selected = game.selection.contains(building.id);
        drawBuildingShape(g, building.kind, building.faction, building.x, building.y,
                building.r, building.owner == Owner.AI, building.progress);
        if (selected) {
            g.setColor(GOLD);
            g.setStroke(new BasicStroke(2.2f));
            g.draw(new Ellipse2D.Double(building.x - building.r - 7, building.y - building.r - 7,
                    (building.r + 7) * 2, (building.r + 7) * 2));
        }
        drawHp(g, building, building.r + 14);
        if (building.rally != null && selected) drawFlag(g, building.rally);
        if (building.kind == BuildingKind.TOWER && selected) {
            g.setColor(new Color(255, 200, 97, 80));
            g.draw(new Ellipse2D.Double(building.x - 235, building.y - 235, 470, 470));
        }
        if (building.repairGlow > 0) {
            g.setColor(new Color(139, 232, 106, 180));
            g.setStroke(new BasicStroke(3f));
            g.draw(new Ellipse2D.Double(building.x - building.r - 8, building.y - building.r - 8,
                    (building.r + 8) * 2, (building.r + 8) * 2));
        }
    }

    private void drawBuildingShape(Graphics2D g, BuildingKind kind, Faction faction,
                                   double x, double y, double radius, boolean enemy, double progress) {
        CompositeState state = new CompositeState(g);
        g.translate(x, y);
        Color body = faction == Faction.ATLANTE ? new Color(0x2C4A63) : new Color(0x4A3A24);
        Color body2 = faction == Faction.ATLANTE ? new Color(0x3F6C8F) : new Color(0x6B5230);
        g.setColor(new Color(0, 0, 0, 80));
        g.fill(new Ellipse2D.Double(-radius * 1.05, radius * 0.15, radius * 2.1, radius * 0.8));
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(enemy ? DANGER : shade(faction.color, 40));

        switch (kind) {
            case BASE -> {
                g.setColor(body);
                if (faction == Faction.ATLANTE) {
                    Polygon polygon = polygon(new double[][]{{0, -radius}, {radius * .85, -radius * .35},
                            {radius * .7, radius * .6}, {-radius * .7, radius * .6}, {-radius * .85, -radius * .35}});
                    g.fill(polygon);
                    g.setColor(faction.color);
                    g.fill(polygon(new double[][]{{0, -radius * .62}, {radius * .34, -radius * .1},
                            {0, radius * .34}, {-radius * .34, -radius * .1}}));
                } else {
                    g.fillArc((int) -radius, (int) -radius * 7 / 10, (int) radius * 2, (int) radius * 2, 0, 180);
                    g.setColor(faction.color);
                    g.fillOval((int) (-radius * .6), (int) (-radius * .5), (int) (radius * 1.2), (int) (radius * .8));
                }
            }
            case FORGE -> {
                g.setColor(body);
                g.fill(new RoundRectangle2D.Double(-radius, -radius * .5, radius * 2, radius * 1.2, 8, 8));
                g.setColor(new Color(0x3A3D45));
                g.fill(new RoundRectangle2D.Double(-radius * .6, -radius * .15, radius * 1.2, radius * .5, 5, 5));
                g.setColor(faction.color);
                g.fill(polygon(new double[][]{{-radius * .55, -radius * .5}, {0, -radius}, {radius * .55, -radius * .5}}));
                g.setColor(GOLD);
                g.fill(new Ellipse2D.Double(-radius * .17, -radius * .07, radius * .34, radius * .34));
            }
            case TOWER -> {
                g.setColor(body);
                g.fill(new RoundRectangle2D.Double(-radius * .62, -radius * .25, radius * 1.24, radius * 1.15, 5, 5));
                g.setColor(faction.color);
                if (faction == Faction.ATLANTE) {
                    g.fill(polygon(new double[][]{{-radius * .85, -radius * .25}, {0, -radius * 1.1}, {radius * .85, -radius * .25}}));
                } else {
                    g.fillArc((int) (-radius * .8), (int) (-radius * 1.05), (int) (radius * 1.6), (int) (radius * 1.6), 0, 180);
                }
                g.setColor(BG);
                g.fill(new Ellipse2D.Double(-radius * .24, -radius * .74, radius * .48, radius * .48));
                g.setColor(shade(faction.color, 60));
                g.fill(new Ellipse2D.Double(-radius * .12, -radius * .62, radius * .24, radius * .24));
            }
            case BARRACKS -> {
                g.setColor(body);
                g.fill(new RoundRectangle2D.Double(-radius, -radius * .7, radius * 2, radius * 1.4, 8, 8));
                g.setColor(body2);
                g.fill(new RoundRectangle2D.Double(-radius * .55, -radius * .4, radius * 1.1, radius * .8, 5, 5));
                g.setColor(faction.color);
                g.fill(new RoundRectangle2D.Double(-radius * .28, -radius * .22, radius * .56, radius * .44, 4, 4));
            }
        }

        if (progress < 1) {
            g.setColor(new Color(0, 0, 0, 140));
            g.fill(new Ellipse2D.Double(-radius, -radius, radius * 2, radius * 2));
            g.setColor(faction.color);
            g.setStroke(new BasicStroke(4f));
            g.drawArc((int) (-radius + 6), (int) (-radius + 6), (int) ((radius - 6) * 2),
                    (int) ((radius - 6) * 2), 90, (int) (-360 * progress));
        }
        state.restore(g);
    }

    private void drawBuildingIcon(Graphics2D g, Building building, int x, int y, int radius) {
        drawBuildingShape(g, building.kind, building.faction, x, y, radius, false, 1);
    }

    private void drawUnit(Graphics2D g, Unit unit) {
        boolean selected = game.selection.contains(unit.id);
        boolean enemy = unit.owner == Owner.AI;
        g.setColor(new Color(0, 0, 0, 95));
        g.fill(new Ellipse2D.Double(unit.x - unit.r * .85, unit.y + unit.r * .2,
                unit.r * 1.7, unit.r * .68));
        if (enemy) {
            g.setColor(new Color(255, 90, 82, 210));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(unit.x - unit.r * .95, unit.y + unit.r * .12,
                    unit.r * 1.9, unit.r * .84));
        }
        if (selected) {
            g.setColor(GOLD);
            g.setStroke(new BasicStroke(2.2f));
            g.draw(new Ellipse2D.Double(unit.x - unit.r * 1.15, unit.y,
                    unit.r * 2.3, unit.r * 1.1));
        }

        BufferedImage image = unitSprite(unit);
        if (image == null) {
            g.setColor(unit.faction.color);
            g.fill(new Ellipse2D.Double(unit.x - unit.r, unit.y - unit.r, unit.r * 2, unit.r * 2));
        } else {
            double h = unit.r * (unit.kind == UnitKind.WORKER ? 2.7 : 3.25);
            double w = h * image.getWidth() / (double) image.getHeight();
            double bob = unit.faction == Faction.MARTIEN
                    ? Math.sin((unit.moving ? unit.animationTime * 13 : System.nanoTime() / 700_000_000.0) + unit.bob) * (unit.moving ? 1.8 : .9)
                    : 0;
            double push = 0;
            if (unit.attackAnimation > 0) {
                double total = unit.ranged ? .30 : .38;
                push = (unit.ranged ? -4 : 10) * Math.sin((1 - unit.attackAnimation / total) * Math.PI);
            }

            CompositeState state = new CompositeState(g);
            g.translate(unit.x + push * unit.face, unit.y);
            int naturalFlip = unit.faction == Faction.ATLANTE ? 1 : -1;
            g.scale(unit.face * naturalFlip, 1);
            g.drawImage(image, (int) (-w / 2), (int) (-h * .84 + bob), (int) w, (int) h, null);
            state.restore(g);
        }
        if (unit.hp < unit.maxHp) drawHp(g, unit, unit.r + 24);
        if (unit.carrying > 0) {
            g.setColor(unit.carryType == ResourceType.METAL ? METAL : WOOD);
            g.fillOval((int) (unit.x + unit.r * .35), (int) (unit.y - unit.r * 1.1), 6, 6);
        }
    }

    private BufferedImage unitSprite(Unit unit) {
        if (unit.faction == Faction.ATLANTE) {
            if (unit.attackAnimation > 0) {
                double progress = 1 - unit.attackAnimation / .38;
                return sprites.get(progress < .42 ? "atl_walk" : "atl_atk");
            }
            return sprites.get(unit.moving ? "atl_walk" : "atl_idle");
        }
        if (unit.attackAnimation > 0) return sprites.get("mar_atk");
        int frame = unit.moving ? (int) (unit.animationTime * 10) % 5 : 0;
        return sprites.get("mar_" + frame);
    }

    private void drawShot(Graphics2D g, Shot shot) {
        double progress = clamp(shot.time / shot.duration, 0, 1);
        double x = lerp(shot.x, shot.targetX, progress);
        double y = lerp(shot.y, shot.targetY, progress);
        g.setColor(new Color(shot.color.getRed(), shot.color.getGreen(), shot.color.getBlue(), 80));
        g.fill(new Ellipse2D.Double(x - 8 * shot.scale, y - 8 * shot.scale, 16 * shot.scale, 16 * shot.scale));
        g.setColor(shot.color);
        double radius = shot.kind == ShotKind.FIRE ? 5.5 : 3.5;
        g.fill(new Ellipse2D.Double(x - radius * shot.scale, y - radius * shot.scale,
                radius * 2 * shot.scale, radius * 2 * shot.scale));
    }

    private void drawParticle(Graphics2D g, Particle particle) {
        double alpha = clamp(particle.life / particle.maxLife, 0, 1);
        g.setColor(new Color(particle.color.getRed(), particle.color.getGreen(), particle.color.getBlue(), (int) (alpha * 255)));
        double radius = particle.radius * alpha + .5;
        g.fill(new Ellipse2D.Double(particle.x - radius, particle.y - radius, radius * 2, radius * 2));
    }

    private void drawPing(Graphics2D g, Ping ping) {
        double radius = (0.5 - ping.life) * 40 + 6;
        g.setColor(new Color(ping.color.getRed(), ping.color.getGreen(), ping.color.getBlue(),
                (int) clamp(ping.life * 1.6 * 255, 0, 255)));
        g.setStroke(new BasicStroke(2f));
        g.draw(new Ellipse2D.Double(ping.x - radius, ping.y - radius, radius * 2, radius * 2));
    }

    private void drawNode(Graphics2D g, ResourceNode node) {
        double scale = .45 + .55 * node.amount / node.maxAmount;
        if (node.type == ResourceType.METAL) {
            g.setColor(new Color(0, 0, 0, 80));
            g.fill(new Ellipse2D.Double(node.x - node.r, node.y + node.r * .1, node.r * 2, node.r * .8));
            g.setColor(new Color(0x8A5638));
            g.fill(polygonAt(node.x, node.y, new double[][]{{-node.r, node.r * .5}, {-node.r * .35, -node.r * .8 * scale},
                    {node.r * .05, node.r * .15}, {node.r * .5, -node.r * .6 * scale}, {node.r, node.r * .5}}));
            g.setColor(METAL);
            g.fillRect((int) node.x - 3, (int) (node.y + node.r * .1), 7, 4);
            g.fillRect((int) (node.x + node.r * .3), (int) (node.y - node.r * .05), 6, 3);
        } else {
            for (int i = -1; i <= 1; i++) {
                drawFlora(g, node.x + i * node.r * .55, node.y + (i != 0 ? node.r * .12 : -node.r * .15),
                        (i != 0 ? .85 : 1.05) * scale + .25);
            }
        }
    }

    private void drawHp(Graphics2D g, Entity entity, double offset) {
        double width = entity.r * 2;
        double x = entity.x - entity.r;
        double y = entity.y - offset;
        double ratio = clamp(entity.hp / entity.maxHp, 0, 1);
        g.setColor(new Color(0, 0, 0, 160));
        g.fill(new Rectangle.Double(x - 1, y - 1, width + 2, 6));
        g.setColor(ratio > .5 ? HEAL : ratio > .25 ? GOLD : DANGER);
        g.fill(new Rectangle.Double(x, y, width * ratio, 4));
    }

    private void drawFlag(Graphics2D g, Point2D.Double point) {
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(2f));
        g.drawLine((int) point.x, (int) point.y, (int) point.x, (int) point.y - 22);
        g.fill(polygonAt(point.x, point.y, new double[][]{{0, -22}, {12, -18}, {0, -14}}));
    }

    private void drawEnd(Graphics2D g) {
        int width = getWidth();
        int height = getHeight();
        g.setPaint(new GradientPaint(0, 0, new Color(0x6D321B), 0, height, new Color(0x160A06)));
        g.fillRect(0, 0, width, height);
        Color titleColor = playerWon ? GOLD : DANGER;
        drawCenteredText(g, playerWon ? "VICTOIRE" : "DÉFAITE", width / 2, height / 2 - 60,
                new Font("SansSerif", Font.BOLD, 72), titleColor);
        String text = playerWon
                ? "Les " + game.playerFaction.displayName + " règnent désormais sur Mars. La cité ennemie n'est plus que ruines."
                : "Ta cité est tombée. Les " + game.aiFaction.displayName + " contrôlent la planète rouge.";
        drawCenteredText(g, text, width / 2, height / 2,
                new Font("SansSerif", Font.PLAIN, 16), INK);
        Rectangle replay = new Rectangle(width / 2 - 100, height / 2 + 38, 200, 46);
        drawButton(g, replay, "REJOUER", INK, PANEL_2, () -> true, () -> {
            screen = Screen.MENU;
            game = null;
        });
    }

    private void drawPanel(Graphics2D g, Rectangle rect) {
        g.setColor(new Color(0x241109));
        g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 9, 9);
        g.setColor(LINE_2);
        g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 9, 9);
    }

    private void drawButton(Graphics2D g, Rectangle rect, String label, Color textColor, Color fill,
                            BooleanSupplier enabled, Runnable action) {
        boolean isEnabled = enabled.getAsBoolean();
        g.setColor(isEnabled ? fill : new Color(50, 35, 28));
        g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 12, 12);
        g.setColor(isEnabled ? LINE_2 : LINE);
        g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 12, 12);
        drawCenteredText(g, label, rect.x + rect.width / 2, rect.y + rect.height / 2 + 5,
                new Font("SansSerif", Font.BOLD, 11), isEnabled ? textColor : MUTED);
        uiButtons.add(new UiButton(rect, enabled, action));
    }

    private boolean handleUiClick(java.awt.Point point) {
        for (int i = uiButtons.size() - 1; i >= 0; i--) {
            UiButton button = uiButtons.get(i);
            if (button.bounds.contains(point) && button.enabled.getAsBoolean()) {
                button.action.run();
                repaint();
                return true;
            }
        }
        return false;
    }

    private void moveCameraFromMinimap(int x, int y) {
        if (game == null || minimapRect.width <= 0 || minimapRect.height <= 0) return;
        double fx = (x - minimapRect.x) / (double) minimapRect.width;
        double fy = (y - minimapRect.y) / (double) minimapRect.height;
        cameraX = fx * WORLD_W - visibleWorldWidth() / 2;
        cameraY = fy * WORLD_H - visibleWorldHeight() / 2;
        clampCamera();
    }

    private void selectArmy() {
        game.selection.clear();
        for (Entity entity : game.entities) {
            if (entity.owner == Owner.PLAYER && entity instanceof Unit unit && unit.kind == UnitKind.WARRIOR) {
                game.selection.add(entity.id);
            }
        }
        if (game.selection.isEmpty()) showToast("Aucun combattant");
    }

    private List<Entity> selectedOwn() {
        if (game == null) return List.of();
        List<Entity> result = new ArrayList<>();
        for (int id : game.selection) {
            game.entities.stream().filter(entity -> entity.id == id && entity.owner == Owner.PLAYER)
                    .findFirst().ifPresent(result::add);
        }
        return result;
    }

    private Entity entityAt(double x, double y, double padding) {
        Entity best = null;
        for (Entity entity : game.entities) {
            if (distance(x, y, entity.x, entity.y) <= entity.r + 4 + padding) {
                if (best == null || entity instanceof Unit) best = entity;
            }
        }
        return best;
    }

    private ResourceNode nodeAt(double x, double y) {
        for (ResourceNode node : game.nodes) {
            if (distance(x, y, node.x, node.y) <= node.r + 8) return node;
        }
        return null;
    }

    private ResourceNode nearestNode(Unit unit, ResourceType type) {
        ResourceNode best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ResourceNode node : game.nodes) {
            if (node.amount <= 0 || (type != null && node.type != type)) continue;
            double d = distance(unit.x, unit.y, node.x, node.y);
            if (d < bestDistance) {
                bestDistance = d;
                best = node;
            }
        }
        return best;
    }

    private Building nearestBase(Unit unit) {
        Building best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : game.entities) {
            if (entity instanceof Building building && building.kind == BuildingKind.BASE
                    && building.owner == unit.owner && building.progress >= 1) {
                double d = distance(unit, building);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = building;
                }
            }
        }
        return best;
    }

    private Entity nearestEnemy(Unit unit, double maxRange) {
        Entity best = null;
        double bestDistance = maxRange;
        for (Entity entity : game.entities) {
            if (entity.owner == unit.owner) continue;
            double d = distance(unit, entity) - entity.r;
            if (d < bestDistance) {
                bestDistance = d;
                best = entity;
            }
        }
        return best;
    }

    private boolean canAfford(Owner owner, Cost cost) {
        ResourceStock stock = game.resources.get(owner);
        return stock.wood >= cost.wood && stock.metal >= cost.metal;
    }

    private void spend(Owner owner, Cost cost) {
        ResourceStock stock = game.resources.get(owner);
        stock.wood -= cost.wood;
        stock.metal -= cost.metal;
    }

    private Cost upgradeCost(Owner owner) {
        int next = game.upgrades.get(owner).level + 1;
        return new Cost(60 * next, 80 * next);
    }

    private int population(Owner owner) {
        return (int) game.entities.stream().filter(entity -> entity.owner == owner && entity instanceof Unit).count();
    }

    private Unit makeUnit(Faction faction, UnitKind kind, double x, double y, Owner owner) {
        UnitStats stats = kind == UnitKind.WORKER ? UnitStats.WORKER : faction.warriorStats;
        Unit unit = new Unit(nextId(), faction, owner, kind, x, y,
                stats.hp, stats.damage, stats.range, stats.speed, stats.attackCooldown,
                stats.ranged, kind == UnitKind.WORKER ? 11 : 14);
        if (kind == UnitKind.WARRIOR && game != null) {
            Upgrade upgrade = game.upgrades.get(owner);
            unit.maxHp += upgrade.hpBonus;
            unit.hp += upgrade.hpBonus;
            unit.damage += upgrade.attackBonus;
        }
        unit.bob = random.nextDouble() * 6;
        unit.animationTime = random.nextDouble() * 3;
        return unit;
    }

    private Building makeBuilding(Faction faction, BuildingKind kind, double x, double y,
                                  Owner owner, boolean instant) {
        double hp = instant ? kind.hp : kind.hp * .15;
        return new Building(nextId(), faction, owner, kind, x, y, hp, kind.hp, kind.radius,
                instant ? 1.0 : 0.0, kind.hp / 6.0);
    }

    private int nextId() {
        return game == null ? random.nextInt(Integer.MAX_VALUE) : game.nextId++;
    }

    private Point2D.Double screenToWorld(double screenX, double screenY) {
        return new Point2D.Double(cameraX + screenX / zoom, cameraY + screenY / zoom);
    }

    private int stageWidth() {
        return Math.max(1, getWidth() - SIDEBAR_W);
    }

    private double visibleWorldWidth() {
        return stageWidth() / zoom;
    }

    private double visibleWorldHeight() {
        return getHeight() / zoom;
    }

    private void clampCamera() {
        cameraX = clamp(cameraX, 0, Math.max(0, WORLD_W - visibleWorldWidth()));
        cameraY = clamp(cameraY, 0, Math.max(0, WORLD_H - visibleWorldHeight()));
    }

    private void showToast(String message) {
        toast = message;
        toastRemaining = 1.7;
    }

    private MapBuild buildMap(long seed, MapPreset preset) {
        BufferedImage terrain = new BufferedImage(WORLD_W, WORLD_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = terrain.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Random mapRandom = new Random(seed);
        int tile = 40;
        Color reg = new Color(161, 80, 46);
        Color reg2 = new Color(186, 101, 60);
        Color dark = new Color(110, 58, 36);
        Color dust = new Color(201, 139, 94);
        Color frost = new Color(214, 203, 192);

        for (int y = 0; y < WORLD_H; y += tile) {
            for (int x = 0; x < WORLD_W; x += tile) {
                double edge = Math.max(0, Math.max((110 - y) / 110.0, (y - (WORLD_H - 110)) / 110.0));
                double wave = Math.sin((Math.min(x, WORLD_W - x) + seed % 1000) * .006)
                        * .5 + Math.cos((y + seed % 700) * .009) * .5;
                Color base;
                if (edge > .55) base = frost;
                else if (edge > .12) base = mix(dust, frost, edge);
                else if (wave > .25) base = mix(reg2, dust, mapRandom.nextDouble() * .45);
                else if (wave < -.35) base = mix(dark, reg, mapRandom.nextDouble() * .5);
                else base = mix(reg, reg2, mapRandom.nextDouble() * .6);
                g.setColor(base);
                g.fillRect(x, y, tile + 1, tile + 1);
                for (int i = 0; i < 6; i++) {
                    int shade = mapRandom.nextInt(35) - 17;
                    g.setColor(shade(base, shade));
                    g.fillRect(x + mapRandom.nextInt(tile), y + mapRandom.nextInt(tile), 2, 2);
                }
            }
        }

        if (preset == MapPreset.CRATERE) {
            drawCrater(g, WORLD_W / 2.0, WORLD_H * .24, 250);
            drawCrater(g, WORLD_W / 2.0, WORLD_H * .76, 250);
        }

        int flora = preset == MapPreset.ROCHERS ? 18 : 26;
        int rocks = preset == MapPreset.ROCHERS ? 170 : 80;
        if (preset == MapPreset.CRATERE) rocks = 90;
        for (int i = 0; i < flora; i++) {
            double x = 140 + mapRandom.nextDouble() * (WORLD_W / 2.0 - 200);
            double y = 170 + mapRandom.nextDouble() * (WORLD_H - 340);
            for (int j = 0; j < 2 + mapRandom.nextInt(3); j++) {
                double ox = mapRandom.nextDouble() * 60 - 30;
                double oy = mapRandom.nextDouble() * 60 - 30;
                drawFlora(g, x + ox, y + oy, .8 + mapRandom.nextDouble() * .3);
                drawFlora(g, WORLD_W - x - ox, y + oy, .8 + mapRandom.nextDouble() * .3);
            }
        }
        for (int i = 0; i < rocks; i++) {
            double x = 90 + mapRandom.nextDouble() * (WORLD_W / 2.0 - 140);
            double y = 120 + mapRandom.nextDouble() * (WORLD_H - 240);
            double scale = .7 + mapRandom.nextDouble() * 1.2;
            drawRock(g, x, y, scale);
            drawRock(g, WORLD_W - x, y, scale);
        }
        g.dispose();

        List<ResourceNode> nodes = new ArrayList<>();
        addMirrorNodes(nodes, 260 + 150, WORLD_H / 2.0 - 125, ResourceType.WOOD, 1400);
        addMirrorNodes(nodes, 260 + 178, WORLD_H / 2.0 + 150, ResourceType.WOOD, 1400);
        addMirrorNodes(nodes, 260 + 545, WORLD_H / 2.0 - 235, ResourceType.METAL, 1500);
        nodes.add(new ResourceNode(ResourceType.WOOD, WORLD_W / 2.0, WORLD_H / 2.0 - 255, 2200));
        nodes.add(new ResourceNode(ResourceType.WOOD, WORLD_W / 2.0, WORLD_H / 2.0 + 255, 2200));
        nodes.add(new ResourceNode(ResourceType.METAL, WORLD_W / 2.0 - 135, WORLD_H / 2.0, 2000));
        nodes.add(new ResourceNode(ResourceType.METAL, WORLD_W / 2.0 + 135, WORLD_H / 2.0, 2000));
        return new MapBuild(terrain, nodes);
    }

    private void addMirrorNodes(List<ResourceNode> nodes, double leftX, double y, ResourceType type, double amount) {
        nodes.add(new ResourceNode(type, leftX, y, amount));
        nodes.add(new ResourceNode(type, WORLD_W - leftX, y, amount));
    }

    private void drawFlora(Graphics2D g, double x, double y, double scale) {
        CompositeState state = new CompositeState(g);
        g.translate(x, y);
        g.scale(scale, scale);
        g.setColor(new Color(0, 0, 0, 75));
        g.fill(new Ellipse2D.Double(-11, 5, 22, 9));
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(0x4A2A1C));
        g.drawLine(0, 8, 0, -6);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(0, -1, -7, -8);
        g.drawLine(0, -3, 7, -9);
        g.setColor(new Color(0x2F6B52));
        g.fillOval(-8, -17, 16, 16);
        g.setColor(new Color(0x3F8F68));
        g.fillOval(-10, -18, 11, 11);
        g.setColor(new Color(0x57B07E));
        g.fillOval(0, -17, 8, 8);
        state.restore(g);
    }

    private void drawRock(Graphics2D g, double x, double y, double scale) {
        CompositeState state = new CompositeState(g);
        g.translate(x, y);
        g.scale(scale, scale);
        g.setColor(new Color(0, 0, 0, 70));
        g.fillOval(-7, 0, 14, 6);
        g.setColor(new Color(0x7A4227));
        g.fill(polygon(new double[][]{{-6, 3}, {-3, -4}, {3, -5}, {6, 3}}));
        g.setColor(new Color(0x96573A));
        g.fill(polygon(new double[][]{{-3, -4}, {3, -5}, {1, 1}}));
        state.restore(g);
    }

    private void drawCrater(Graphics2D g, double x, double y, double radius) {
        g.setColor(new Color(70, 32, 20, 150));
        g.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
        g.setColor(new Color(206, 140, 95, 120));
        g.setStroke(new BasicStroke((float) Math.max(3, radius * .05)));
        g.draw(new Ellipse2D.Double(x - radius * .92, y - radius * .92, radius * 1.84, radius * 1.84));
        g.setColor(new Color(60, 28, 16, 130));
        g.setStroke(new BasicStroke((float) Math.max(2, radius * .03)));
        g.draw(new Ellipse2D.Double(x - radius * .55, y - radius * .55, radius * 1.1, radius * 1.1));
    }

    private static Polygon polygon(double[][] points) {
        Polygon polygon = new Polygon();
        for (double[] point : points) polygon.addPoint((int) Math.round(point[0]), (int) Math.round(point[1]));
        return polygon;
    }

    private static Polygon polygonAt(double x, double y, double[][] points) {
        Polygon polygon = new Polygon();
        for (double[] point : points) polygon.addPoint((int) Math.round(x + point[0]), (int) Math.round(y + point[1]));
        return polygon;
    }

    private void drawCenteredText(Graphics2D g, String text, int centerX, int baselineY, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics metrics = g.getFontMetrics(font);
        g.drawString(text, centerX - metrics.stringWidth(text) / 2, baselineY);
    }

    private void drawWrappedText(Graphics2D g, String text, int x, int y, int maxWidth,
                                 Font font, Color color, int lineHeight) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics metrics = g.getFontMetrics(font);
        StringBuilder line = new StringBuilder();
        int currentY = y;
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) > maxWidth && !line.isEmpty()) {
                g.drawString(line.toString(), x, currentY);
                currentY += lineHeight;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) g.drawString(line.toString(), x, currentY);
    }

    private static double distance(Entity a, Entity b) {
        return distance(a.x, a.y, b.x, b.y);
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, y1 - y2);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static Color mix(Color a, Color b, double t) {
        t = clamp(t, 0, 1);
        return new Color(
                (int) Math.round(lerp(a.getRed(), b.getRed(), t)),
                (int) Math.round(lerp(a.getGreen(), b.getGreen(), t)),
                (int) Math.round(lerp(a.getBlue(), b.getBlue(), t))
        );
    }

    private static Color shade(Color color, int amount) {
        return new Color(
                (int) clamp(color.getRed() + amount, 0, 255),
                (int) clamp(color.getGreen() + amount, 0, 255),
                (int) clamp(color.getBlue() + amount, 0, 255)
        );
    }

    private enum Screen { MENU, PLAYING, END }
    private enum Owner { PLAYER, AI }
    private enum UnitKind {
        WORKER("Serviteur"), WARRIOR("Combattant");
        final String displayName;
        UnitKind(String displayName) { this.displayName = displayName; }
    }
    private enum ResourceType { WOOD, METAL }
    private enum ShotKind { FIRE, BOLT }

    private enum MapPreset {
        RANDOM("Aléatoire"),
        PLAINES("Plaines de Chryse"),
        ROCHERS("Champ de rochers"),
        CRATERE("Grand cratère");
        final String label;
        MapPreset(String label) { this.label = label; }
    }

    private enum Faction {
        ATLANTE("Atlantes", "Gardiens de l'héritage", new Color(0x4FB8FF), new Color(0xBFE6FF), false,
                "Guerriers en armure d'orichalque. Très résistants, ils brisent les lignes ennemies au corps à corps.",
                new UnitStats(170, 26, 34, 80, .95, false)),
        MARTIEN("Martiens", "Télépathes des sables", new Color(0x9DFF3D), new Color(0x9DFF3D), true,
                "Créatures frêles mais redoutables. Elles lévitent et lancent des sphères d'énergie à longue portée.",
                new UnitStats(105, 19, 178, 76, 1.05, true));

        final String displayName;
        final String title;
        final Color color;
        final Color shotColor;
        final boolean ranged;
        final String description;
        final UnitStats warriorStats;

        Faction(String displayName, String title, Color color, Color shotColor, boolean ranged,
                String description, UnitStats warriorStats) {
            this.displayName = displayName;
            this.title = title;
            this.color = color;
            this.shotColor = shotColor;
            this.ranged = ranged;
            this.description = description;
            this.warriorStats = warriorStats;
        }
    }

    private enum BuildingKind {
        BASE("Cité", "⌂", 1700, 46, new Cost(0, 0)),
        BARRACKS("Caserne", "⌂", 900, 34, new Cost(120, 20)),
        FORGE("Forge", "✦", 900, 32, new Cost(140, 90)),
        TOWER("Tour", "♜", 750, 26, new Cost(80, 120));

        final String displayName;
        final String icon;
        final double hp;
        final double radius;
        final Cost cost;

        BuildingKind(String displayName, String icon, double hp, double radius, Cost cost) {
            this.displayName = displayName;
            this.icon = icon;
            this.hp = hp;
            this.radius = radius;
            this.cost = cost;
        }
    }

    private record Cost(int wood, int metal) {}
    private record UnitStats(double hp, double damage, double range, double speed,
                             double attackCooldown, boolean ranged) {
        private static final UnitStats WORKER = new UnitStats(60, 5, 24, 94, 1.0, false);
    }
    private record MapBuild(BufferedImage terrain, List<ResourceNode> nodes) {}
    private record UiButton(Rectangle bounds, BooleanSupplier enabled, Runnable action) {}
    private record ActionSpec(String icon, String label, String subtitle, Cost cost,
                              BooleanSupplier enabled, Runnable action) {}

    private static final class Game {
        final Faction playerFaction;
        final Faction aiFaction;
        final MapPreset preset;
        final EnumMap<Owner, ResourceStock> resources = new EnumMap<>(Owner.class);
        final EnumMap<Owner, Upgrade> upgrades = new EnumMap<>(Owner.class);
        final List<Entity> entities = new ArrayList<>();
        final List<ResourceNode> nodes = new ArrayList<>();
        final List<Shot> shots = new ArrayList<>();
        final List<Particle> particles = new ArrayList<>();
        final List<Ping> pings = new ArrayList<>();
        final Set<Integer> selection = new HashSet<>();
        BufferedImage terrain;
        BuildingKind placing;
        boolean over;
        int nextId = 1;
        double aiDecisionTimer;
        double aiAttackTimer = 72;
        double aiBuildTimer = 6;

        Game(Faction playerFaction, Faction aiFaction, MapPreset preset) {
            this.playerFaction = playerFaction;
            this.aiFaction = aiFaction;
            this.preset = preset;
            resources.put(Owner.PLAYER, new ResourceStock(START_RES.wood, START_RES.metal));
            resources.put(Owner.AI, new ResourceStock(START_RES.wood, START_RES.metal));
            upgrades.put(Owner.PLAYER, new Upgrade());
            upgrades.put(Owner.AI, new Upgrade());
        }
    }

    private static class Entity {
        final int id;
        final Faction faction;
        final Owner owner;
        double x;
        double y;
        double hp;
        double maxHp;
        final double r;

        Entity(int id, Faction faction, Owner owner, double x, double y,
               double hp, double maxHp, double r) {
            this.id = id;
            this.faction = Objects.requireNonNull(faction);
            this.owner = Objects.requireNonNull(owner);
            this.x = x;
            this.y = y;
            this.hp = hp;
            this.maxHp = maxHp;
            this.r = r;
        }
    }

    private static final class Unit extends Entity {
        final UnitKind kind;
        double damage;
        final double range;
        final double speed;
        final double attackCooldown;
        final boolean ranged;
        Point2D.Double moveTarget;
        Entity attackTarget;
        ResourceNode harvestNode;
        ResourceType carryType;
        double carrying;
        double harvestCooldown;
        double attackCooldownRemaining;
        Building buildTask;
        Building repairTask;
        double animationTime;
        double attackAnimation;
        double bob;
        boolean moving;
        int face = 1;

        Unit(int id, Faction faction, Owner owner, UnitKind kind, double x, double y,
             double hp, double damage, double range, double speed, double attackCooldown,
             boolean ranged, double r) {
            super(id, faction, owner, x, y, hp, hp, r);
            this.kind = kind;
            this.damage = damage;
            this.range = range;
            this.speed = speed;
            this.attackCooldown = attackCooldown;
            this.ranged = ranged;
        }
    }

    private static final class Building extends Entity {
        final BuildingKind kind;
        final Deque<UnitKind> queue = new ArrayDeque<>();
        Point2D.Double rally;
        double productionCooldown;
        double progress;
        double buildSpeed;
        double fireCooldown;
        double repairGlow;

        Building(int id, Faction faction, Owner owner, BuildingKind kind,
                 double x, double y, double hp, double maxHp, double r,
                 double progress, double buildSpeed) {
            super(id, faction, owner, x, y, hp, maxHp, r);
            this.kind = kind;
            this.progress = progress;
            this.buildSpeed = buildSpeed;
        }
    }

    private static final class ResourceNode {
        final ResourceType type;
        final double x;
        final double y;
        final double maxAmount;
        final double r;
        double amount;

        ResourceNode(ResourceType type, double x, double y, double amount) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.amount = amount;
            this.maxAmount = amount;
            this.r = type == ResourceType.METAL ? 28 : 26;
        }
    }

    private static final class ResourceStock {
        double wood;
        double metal;
        ResourceStock(double wood, double metal) {
            this.wood = wood;
            this.metal = metal;
        }
    }

    private static final class Upgrade {
        int level;
        int attackBonus;
        int hpBonus;
    }

    private static final class Shot {
        final double x;
        final double y;
        final double targetX;
        final double targetY;
        final Color color;
        final ShotKind kind;
        final double scale;
        final double duration;
        double time;

        Shot(double x, double y, double targetX, double targetY, Color color,
             ShotKind kind, double scale, double duration) {
            this.x = x;
            this.y = y;
            this.targetX = targetX;
            this.targetY = targetY;
            this.color = color;
            this.kind = kind;
            this.scale = scale;
            this.duration = duration;
        }
    }

    private static final class Particle {
        double x;
        double y;
        final double vx;
        double vy;
        double life;
        final double maxLife;
        final Color color;
        final double radius;

        Particle(double x, double y, double vx, double vy, double life, Color color, double radius) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.life = life;
            this.maxLife = life;
            this.color = color;
            this.radius = radius;
        }
    }

    private static final class Ping {
        final double x;
        final double y;
        double life;
        final Color color;
        Ping(double x, double y, double life, Color color) {
            this.x = x;
            this.y = y;
            this.life = life;
            this.color = color;
        }
    }

    private static final class CompositeState {
        private final AffineTransform transform;
        private final java.awt.Composite composite;
        private final java.awt.Stroke stroke;

        CompositeState(Graphics2D g) {
            transform = g.getTransform();
            composite = g.getComposite();
            stroke = g.getStroke();
        }

        void restore(Graphics2D g) {
            g.setTransform(transform);
            g.setComposite(composite);
            g.setStroke(stroke);
        }
    }
}
