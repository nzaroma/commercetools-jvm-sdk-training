package handson;

import io.sphere.sdk.carts.*;
import io.sphere.sdk.carts.commands.CartCreateCommand;
import io.sphere.sdk.carts.commands.CartDeleteCommand;
import io.sphere.sdk.carts.commands.CartUpdateCommand;
import io.sphere.sdk.carts.commands.updateactions.AddCustomLineItem;
import io.sphere.sdk.carts.commands.updateactions.AddLineItem;
import io.sphere.sdk.carts.commands.updateactions.RemoveCustomLineItem;
import io.sphere.sdk.carts.commands.updateactions.RemoveLineItem;
import io.sphere.sdk.carts.queries.CartByIdGet;
import io.sphere.sdk.carts.queries.CartQuery;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.client.SphereClientConfig;
import io.sphere.sdk.client.SphereClientFactory;
import io.sphere.sdk.client.SphereRequest;
import io.sphere.sdk.commands.UpdateAction;
import io.sphere.sdk.models.LocalizedString;
import io.sphere.sdk.products.ProductProjection;
import io.sphere.sdk.products.ProductVariant;
import io.sphere.sdk.products.queries.ProductProjectionByIdGet;
import io.sphere.sdk.products.queries.ProductProjectionQuery;
import io.sphere.sdk.queries.QueryPredicate;
import io.sphere.sdk.types.*;
import io.sphere.sdk.types.commands.TypeCreateCommand;
import io.sphere.sdk.types.commands.TypeDeleteCommand;
import org.apache.commons.lang3.RandomStringUtils;
import org.javamoney.moneta.Money;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

import static io.sphere.sdk.models.DefaultCurrencyUnits.EUR;
import static io.sphere.sdk.models.TextInputHint.*;
import static io.sphere.sdk.products.ProductProjectionType.CURRENT;
import static io.sphere.sdk.utils.SetUtils.asSet;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Locale.ENGLISH;

public class Main {
    public static final String CUSTOM_LINE_ITEM_TYPE_KEY = RandomStringUtils.randomAlphanumeric(10);
    public static final String CART_TYPE_KEY = RandomStringUtils.randomAlphanumeric(10);
    public static final String PRODUCT_ID_FIELD = "productId";
    public static final String PRODUCT_SLUG_FIELD = "productSlug";
    public static final String PRODUCT_SKU_FIELD = "productSku";
    public static final String CUSTOMER_NUMBER_FIELD = "customerNumber";

    public static void main(String[] args) throws IOException {
        final Properties prop = loadCommercetoolsPlatformProperties();
        final String projectKey = prop.getProperty("projectKey");
        final String clientId = prop.getProperty("clientId");
        final String clientSecret = prop.getProperty("clientSecret");
        final SphereClientConfig clientConfig = SphereClientConfig.of(projectKey, clientId, clientSecret);

        try(final SphereClient client = SphereClientFactory.of().createClient(clientConfig)) {
            // Done only once at creation of the project
            final Type typeForCustomLineItems = createTypeForExtendedCustomLineItems(client);
            System.err.println("Created typeForCustomLineItems for extending custom line items with product information:");
            System.err.println(typeForCustomLineItems);

            // Done only once at creation of the project
            final Type typeForCarts = createTypeForExtendedCarts(client);
            System.err.println("Created typeForCart for extending carts with customer number:");
            System.err.println(typeForCarts);

            // We create a cart at some moment, e.g. when the customer logs in
            final String customerNumber = "34563463456";
            final Cart cart = createCart(client, customerNumber);
            printCart(cart, "Empty cart created:");

            final String sessionCartId = cart.getId();

            // Many pages are clicked...

            final Cart cartFetchedFromSession = execute(client, CartByIdGet.of(sessionCartId));

            // When clicked on "add to cart" we add a product to the cart, which contains a non-customized price
            final int quantity = 3;
            final ProductProjection product = getSomeProduct(client);
            final ProductVariant variant = product.getMasterVariant(); // TODO find variant requested
            final Cart cartWithLineItem = addProductToCart(client, cartFetchedFromSession, product, variant, quantity);
            printCart(cartWithLineItem, "Cart with regular line items:");

            // When clicked on "refresh price" we replace the line items with custom line items, which contain the price from the external system
            final Cart cartWithProductAndPrice = updateCartWithCustomPrices(client, cartWithLineItem);
            printCart(cartWithProductAndPrice, "Cart with custom line items with customized price:");

            // When after having refreshed the price the customer decides to add more units
            final int additionalQuantity = 2;
            final Cart cartWithMoreProductsAndPrices = addProductToCartWithCustomizedPrice(client, cartWithProductAndPrice, product, variant, additionalQuantity);
            printCart(cartWithMoreProductsAndPrices, "Cart with 2 additional units with the same product and customized price");

            // When after having refreshed the price the customer decides to add more products
            final int anotherQuantity = 2;
            final ProductProjection someOtherProduct = getSomeOtherProduct(client);
            final ProductVariant someOtherVariant = someOtherProduct.getMasterVariant();
            final Cart finalCart = addProductToCartWithCustomizedPrice(client, cartWithMoreProductsAndPrices, someOtherProduct, someOtherVariant, anotherQuantity);
            printCart(finalCart, "Cart with an additional custom line item with another product and customized price");

            // On "Orders history" page, list carts with this customer number
            printCartsByCustomerNumber(client, customerNumber);

            // Clean up project to avoid conflicts for next iteration
            cleanUpProject(client, typeForCustomLineItems, finalCart);
        }
    }

    private static void printCartsByCustomerNumber(final SphereClient client, final String customerNumber) {
        System.err.println("");
        System.err.println("List of carts with customer number " + customerNumber);

        final QueryPredicate<Cart> queryPredicate = QueryPredicate.of(String.format("custom(fields(customerNumber = \"%s\"))", customerNumber));
        execute(client, CartQuery.of().withPredicates(queryPredicate)).getResults().forEach(System.err::println);
    }

    private static void printCart(final Cart cart, final String message) {
        System.err.println("");
        System.err.println(message);
        System.err.println("Customer number: " + cart.getCustom().getFieldAsString(CUSTOMER_NUMBER_FIELD));
        System.err.println("Line items:");
        cart.getLineItems().forEach(System.err::println);
        System.err.println("Custom line items:");
        cart.getCustomLineItems().forEach(System.err::println);
    }

    private static Cart updateCartWithCustomPrices(final SphereClient client, final Cart cart) {
        final List<UpdateAction<Cart>> replaceLineItemsUpdateActions = new ArrayList<>();
        for (final LineItem lineItem : cart.getLineItems()) {
            final ProductProjection product = getProductFromLineItem(client, lineItem);
            final ProductVariant variant = product.findVariantBySky(lineItem.getVariant().getSku()).get();
            replaceLineItemsUpdateActions.addAll(actionToReplaceLineItemWithCustomLineItem(product, variant, lineItem));
        }
        return execute(client, CartUpdateCommand.of(cart, replaceLineItemsUpdateActions));
    }

    private static List<UpdateAction<Cart>> actionToReplaceLineItemWithCustomLineItem(final ProductProjection product,
                                                                                      final ProductVariant variant,
                                                                                      final LineItem lineItem) {
        return asList(RemoveLineItem.of(lineItem), actionToCreateCustomLineItem(product, variant, lineItem.getQuantity()));
    }

    private static UpdateAction<Cart> actionToCreateCustomLineItem(final ProductProjection product, final ProductVariant variant, final long quantity) {
        final CustomLineItemDraft customLineItemDraft = createCustomLineItemFromProduct(product, variant, quantity);
        final CustomFieldsDraft customFieldsDraft = CustomFieldsDraft.ofTypeKeyAndObjects(CUSTOM_LINE_ITEM_TYPE_KEY, extractInfoFromProduct(product));
        return AddCustomLineItem.of(customLineItemDraft).withCustom(customFieldsDraft);
    }

    private static Map<String, Object> extractInfoFromProduct(final ProductProjection product) {
        final Map<String, Object> productInfo = new HashMap<>();
        productInfo.put(PRODUCT_ID_FIELD, product.getId());
        productInfo.put(PRODUCT_SLUG_FIELD, product.getSlug());
        productInfo.put(PRODUCT_SKU_FIELD, product.getMasterVariant().getSku());
        return productInfo;
    }

    private static CustomLineItemDraft createCustomLineItemFromProduct(final ProductProjection product, final ProductVariant variant, final long quantity) {
        return CustomLineItemDraft.of(product.getName(), variant.getSku(), getFinalPriceFromExternalSystem(), product.getTaxCategory(), quantity);
    }

    private static Cart addProductToCart(final SphereClient client, final Cart cart, final ProductProjection product,
                                         final ProductVariant variant, final int quantity) {
        return execute(client, CartUpdateCommand.of(cart, AddLineItem.of(product, variant.getId(), quantity)));
    }

    private static Cart addProductToCartWithCustomizedPrice(final SphereClient client, final Cart cart, final ProductProjection product,
                                                            final ProductVariant variant, final int quantity) {
        final Optional<CustomLineItem> customLineItemForProduct = findCustomLineItemForProduct(cart, variant.getSku());
        if (customLineItemForProduct.isPresent()) {
            final long newQuantity = quantity + customLineItemForProduct.get().getQuantity();
            final UpdateAction<Cart> addToCartAction = actionToCreateCustomLineItem(product, variant, newQuantity);
            return execute(client, CartUpdateCommand.of(cart, asList(RemoveCustomLineItem.of(customLineItemForProduct.get()), addToCartAction)));
        } else {
            return execute(client, CartUpdateCommand.of(cart, actionToCreateCustomLineItem(product, variant, quantity)));
        }
    }

    private static Optional<CustomLineItem> findCustomLineItemForProduct(final Cart cart, final String sku) {
        return cart.getCustomLineItems().stream()
                .filter(cli -> cli.getSlug().equals(sku))
                .findFirst();
    }

    private static Type createTypeForExtendedCarts(final SphereClient client) {
        // This is only executed (or created) once, this doesn't need to be here in a real example
        final TypeDraft typeDraft = TypeDraftBuilder.of(CART_TYPE_KEY, LocalizedString.of(ENGLISH, "Cart with customer number"), asSet("order"))
                .fieldDefinitions(singletonList(
                        FieldDefinition.of(StringType.of(), CUSTOMER_NUMBER_FIELD, LocalizedString.of(ENGLISH, "Customer number"), false, SINGLE_LINE)
                )).build();
        return execute(client, TypeCreateCommand.of(typeDraft));
    }

    private static Type createTypeForExtendedCustomLineItems(final SphereClient client) {
        // This is only executed (or created) once, this doesn't need to be here in a real example
        final TypeDraft typeDraft = TypeDraftBuilder.of(CUSTOM_LINE_ITEM_TYPE_KEY, LocalizedString.of(ENGLISH, "CustomLineItem with product"), asSet("custom-line-item"))
                .fieldDefinitions(asList(
                        FieldDefinition.of(StringType.of(), PRODUCT_ID_FIELD, LocalizedString.of(ENGLISH, "Product ID"), true, SINGLE_LINE),
                        FieldDefinition.of(LocalizedStringType.of(), PRODUCT_SLUG_FIELD, LocalizedString.of(ENGLISH, "Product Slug"), true, SINGLE_LINE),
                        FieldDefinition.of(StringType.of(), PRODUCT_SKU_FIELD, LocalizedString.of(ENGLISH, "SKU"), true, SINGLE_LINE)))
                .build();
        return execute(client, TypeCreateCommand.of(typeDraft));
    }

    private static ProductProjection getProductFromLineItem(final SphereClient client, final LineItem lineItem) {
        return execute(client, ProductProjectionByIdGet.of(lineItem.getProductId(), CURRENT));
    }

    private static Cart createCart(final SphereClient client, final String customerNumber) {
        final CustomFieldsDraft customFieldsDraft = CustomFieldsDraftBuilder.ofTypeKey(CART_TYPE_KEY)
                .addObject(CUSTOMER_NUMBER_FIELD, customerNumber)
                .build();
        return execute(client, CartCreateCommand.of(CartDraft.of(EUR).withCustom(customFieldsDraft)));
    }

    private static Money getFinalPriceFromExternalSystem() {
        return Money.of(BigDecimal.TEN, EUR); // TODO fetch price from external system
    }

    private static ProductProjection getSomeProduct(final SphereClient client) {
        return execute(client, ProductProjectionQuery.ofCurrent()).getResults().get(0);
    }

    private static ProductProjection getSomeOtherProduct(final SphereClient client) {
        return execute(client, ProductProjectionQuery.ofCurrent()).getResults().get(1);
    }

    private static Properties loadCommercetoolsPlatformProperties() throws IOException {
        final Properties prop = new Properties();
        prop.load(Main.class.getClassLoader().getResourceAsStream("dev.properties"));
        return prop;
    }

    private static void cleanUpProject(final SphereClient client, final Type typeForCustomLineItems, final Cart cartWithProductAndPrice) {
        execute(client, CartDeleteCommand.of(cartWithProductAndPrice));
        execute(client, TypeDeleteCommand.of(typeForCustomLineItems));
    }

    private static <T> T execute(final SphereClient client, final SphereRequest<T> sphereRequest) {
        return client.execute(sphereRequest).toCompletableFuture().join();
    }
}
