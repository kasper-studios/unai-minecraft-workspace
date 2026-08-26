package xyz.kasperstudios.unai.bridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.*;

/**
 * 3D A* Pathfinder for UnAI Fake Player in Forge 1.21.1.
 * Adapted from KasHub Pathfinding Engine.
 */
public class AStarPathfinder {

    public static class PathOptions {
        public float targetRadius = 1.2f;
        public int maxFallDistance = 4;
        public boolean avoidDanger = true;
        public boolean allowSwim = true;
        public int maxIterations = 6000;
    }

    private static class Node {
        final BlockPos pos;
        final Node parent;
        final double gScore;
        final double fScore;

        Node(BlockPos pos, Node parent, double gScore, double fScore) {
            this.pos = pos;
            this.parent = parent;
            this.gScore = gScore;
            this.fScore = fScore;
        }
    }

    private static class Neighbor {
        final BlockPos pos;
        final double cost;

        Neighbor(BlockPos pos, double cost) {
            this.pos = pos;
            this.cost = cost;
        }
    }

    private static final Set<net.minecraft.world.level.block.Block> DANGEROUS_BLOCKS = Set.of(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.MAGMA_BLOCK,
            Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.POWDER_SNOW
    );

    public static List<BlockPos> findPath(ServerLevel world, BlockPos start, BlockPos end, PathOptions options) {
        if (options == null) options = new PathOptions();
        if (start.equals(end)) return List.of(end);

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fScore));
        Map<BlockPos, Node> allNodes = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        Node startNode = new Node(start, null, 0, heuristic(start, end));
        openSet.add(startNode);
        allNodes.put(start, startNode);

        int iterations = 0;

        while (!openSet.isEmpty() && iterations < options.maxIterations) {
            iterations++;
            Node current = openSet.poll();

            if (current.pos.equals(end) || current.pos.distSqr(end) <= (options.targetRadius * options.targetRadius)) {
                return reconstructPath(current);
            }

            closedSet.add(current.pos);

            for (Neighbor neighbor : getNeighbors(world, current.pos, options)) {
                if (closedSet.contains(neighbor.pos)) continue;

                double tentativeG = current.gScore + neighbor.cost;
                Node neighborNode = allNodes.get(neighbor.pos);

                if (neighborNode == null) {
                    neighborNode = new Node(neighbor.pos, current, tentativeG, tentativeG + heuristic(neighbor.pos, end));
                    allNodes.put(neighbor.pos, neighborNode);
                    openSet.add(neighborNode);
                } else if (tentativeG < neighborNode.gScore) {
                    openSet.remove(neighborNode);
                    neighborNode = new Node(neighbor.pos, current, tentativeG, tentativeG + heuristic(neighbor.pos, end));
                    allNodes.put(neighbor.pos, neighborNode);
                    openSet.add(neighborNode);
                }
            }
        }

        return null;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }

    private static List<BlockPos> reconstructPath(Node node) {
        List<BlockPos> path = new ArrayList<>();
        while (node != null) {
            path.add(0, node.pos);
            node = node.parent;
        }
        return path;
    }

    private static List<Neighbor> getNeighbors(ServerLevel world, BlockPos pos, PathOptions options) {
        List<Neighbor> neighbors = new ArrayList<>();

        BlockPos[] horizontal = {
                pos.north(), pos.south(), pos.east(), pos.west()
        };

        BlockPos[] diagonal = {
                pos.north().east(), pos.north().west(),
                pos.south().east(), pos.south().west()
        };

        // 1. Horizontal movement
        for (BlockPos dir : horizontal) {
            if (isWalkable(world, dir, options) && world.getBlockState(dir.above()).getCollisionShape(world, dir.above()).isEmpty()) {
                neighbors.add(new Neighbor(dir, 1.0));
            }

            // Step up / jump up 1 block
            BlockPos up = dir.above();
            if (isWalkable(world, up, options) && canJumpTo(world, pos, up)) {
                neighbors.add(new Neighbor(up, 1.5));
            }

            // Step down / falling
            for (int fall = 1; fall <= options.maxFallDistance; fall++) {
                BlockPos down = dir.below(fall);
                if (isWalkable(world, down, options)) {
                    if (hasHeadroom(world, pos, down, fall)) {
                        neighbors.add(new Neighbor(down, 1.0 + fall * 0.4));
                    }
                    break;
                }
            }
        }

        // 2. Diagonal movement (check horizontal corners to avoid getting stuck in walls)
        for (int i = 0; i < diagonal.length; i++) {
            BlockPos diag = diagonal[i];
            if (isWalkable(world, diag, options)) {
                BlockPos h1, h2;
                if (i == 0) { h1 = pos.north(); h2 = pos.east(); }
                else if (i == 1) { h1 = pos.north(); h2 = pos.west(); }
                else if (i == 2) { h1 = pos.south(); h2 = pos.east(); }
                else { h1 = pos.south(); h2 = pos.west(); }

                if (isWalkable(world, h1, options) && isWalkable(world, h2, options)) {
                    neighbors.add(new Neighbor(diag, 1.414));
                }
            }
        }

        // 3. Ladders & Climbing
        if (isClimbable(world, pos)) {
            BlockPos up = pos.above();
            if (isWalkable(world, up, options) || isClimbable(world, up)) {
                neighbors.add(new Neighbor(up, 1.2));
            }
            BlockPos down = pos.below();
            if (isWalkable(world, down, options) || isClimbable(world, down)) {
                neighbors.add(new Neighbor(down, 1.0));
            }
        }

        // 4. Water Swimming
        if (options.allowSwim && isInWater(world, pos)) {
            BlockPos up = pos.above();
            BlockPos down = pos.below();
            if (isInWater(world, up) || world.getBlockState(up).getCollisionShape(world, up).isEmpty()) {
                neighbors.add(new Neighbor(up, 1.4));
            }
            if (isInWater(world, down)) {
                neighbors.add(new Neighbor(down, 1.0));
            }
        }

        return neighbors;
    }

    private static boolean isWalkable(ServerLevel world, BlockPos pos, PathOptions options) {
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.above());
        BlockState ground = world.getBlockState(pos.below());

        if (options.avoidDanger) {
            if (isDangerous(world, pos) || isDangerous(world, pos.below()) || isDangerous(world, pos.above())) {
                return false;
            }
        }

        boolean feetPassable = feet.getCollisionShape(world, pos).isEmpty() || isClimbable(world, pos) || isInWater(world, pos);
        boolean headPassable = head.getCollisionShape(world, pos.above()).isEmpty();

        if (feet.getBlock() instanceof LeavesBlock || head.getBlock() instanceof LeavesBlock) {
            return false;
        }

        boolean hasGround = !ground.getCollisionShape(world, pos.below()).isEmpty() ||
                isInWater(world, pos.below()) ||
                isClimbable(world, pos.below()) ||
                isClimbable(world, pos) ||
                pos.getY() <= world.getMinBuildHeight();

        return feetPassable && headPassable && hasGround;
    }

    private static boolean isDangerous(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return DANGEROUS_BLOCKS.contains(state.getBlock());
    }

    private static boolean isClimbable(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof LadderBlock ||
                state.getBlock() instanceof VineBlock ||
                state.is(Blocks.SCAFFOLDING);
    }

    private static boolean isInWater(ServerLevel world, BlockPos pos) {
        FluidState fluid = world.getFluidState(pos);
        return fluid.is(FluidTags.WATER);
    }

    private static boolean canJumpTo(ServerLevel world, BlockPos from, BlockPos to) {
        boolean onGround = !world.getBlockState(from.below()).getCollisionShape(world, from.below()).isEmpty() ||
                isInWater(world, from) || isClimbable(world, from);
        if (!onGround) return false;

        // Headroom for jump
        return world.getBlockState(from.above(2)).getCollisionShape(world, from.above(2)).isEmpty();
    }

    private static boolean hasHeadroom(ServerLevel world, BlockPos from, BlockPos to, int fallDistance) {
        for (int i = 1; i <= fallDistance; i++) {
            BlockPos check = from.below(i);
            if (!world.getBlockState(check).getCollisionShape(world, check).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
