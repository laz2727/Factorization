package factorization.api;

/**
 * TileEntities that implement this interface can have light reflected onto them by mirrors & such
 */
public interface IReflectionTarget {
    void addReflector(int strength);

    void removeReflector(int strength);
}
