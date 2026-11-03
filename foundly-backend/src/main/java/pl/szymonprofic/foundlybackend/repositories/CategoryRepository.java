package pl.szymonprofic.foundlybackend.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.szymonprofic.foundlybackend.models.Category;
import java.util.*;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    Optional<Category> findByCode(String code);
    List<Category> findByActiveTrueOrderBySortOrderAsc();
}
