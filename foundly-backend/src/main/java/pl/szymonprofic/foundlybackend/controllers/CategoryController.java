package pl.szymonprofic.foundlybackend.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import pl.szymonprofic.foundlybackend.repositories.CategoryRepository;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryRepository categories;
    public record CategoryResponse(UUID id, String code, String name) {}

    @GetMapping("/api/categories")
    public List<CategoryResponse> list() {
        return categories.findByActiveTrueOrderBySortOrderAsc().stream()
                .map(c -> new CategoryResponse(c.getId(), c.getCode(), c.getName())).toList();
    }
}
