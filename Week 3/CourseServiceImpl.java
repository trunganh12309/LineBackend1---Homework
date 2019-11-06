package vn.edu.topica.eco.api.middleware.service.v1.impl;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import one.util.streamex.StreamEx;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import vn.edu.topica.eco.api.middleware.constant.CacheConst;
import vn.edu.topica.eco.api.middleware.constant.LocalConst;
import vn.edu.topica.eco.api.middleware.constant.MagentoConst.MagentoProductStatus;
import vn.edu.topica.eco.api.middleware.constant.PikamonConst;
import vn.edu.topica.eco.api.middleware.exception.pikamon.ActivateCourseException;
import vn.edu.topica.eco.api.middleware.model.magento.MagentoPromotion;
import vn.edu.topica.eco.api.middleware.model.magento.cart.MagentoCart;
import vn.edu.topica.eco.api.middleware.model.magento.course.MagentoCourse;
import vn.edu.topica.eco.api.middleware.model.magento.course.PersonalCourses;
import vn.edu.topica.eco.api.middleware.model.magento.course.wrapper.CoursesWrapper;
import vn.edu.topica.eco.api.middleware.model.magento.wishlist.MagentoWishlistItem;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.activatecourse.ActivateErrorPayload;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.category.FilterChoice;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.category.MobileCategory;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.category.ResultOrder;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.course.PageInfo;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.course.PersonalCourseItems;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.course.PreviewCourse;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.course.RelatedCourse;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.course.wrapper.TopOfCatWrapper;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.course.wrapper.TopOfMarket;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.coursedetail.CourseDetail;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.coursedetail.config.CourseDetailConfig;
import vn.edu.topica.eco.api.middleware.model.mobile.v1.coursedetail.curriculum.Curriculum;
import vn.edu.topica.eco.api.middleware.model.pikamon.ActivatedError;
import vn.edu.topica.eco.api.middleware.model.pikamon.response.ActivateErrorWrapper;
import vn.edu.topica.eco.api.middleware.model.pikamon.response.ActivateSuccessWrapper;
import vn.edu.topica.eco.api.middleware.model.support.Response;
import vn.edu.topica.eco.api.middleware.model.support.adapter.v1.PreviewCourseAdapter;
import vn.edu.topica.eco.api.middleware.model.support.adapter.v1.activatecourse.ActivatePayloadAdapter;
import vn.edu.topica.eco.api.middleware.model.support.adapter.v1.coursedetail.CourseDetailAdapter;
import vn.edu.topica.eco.api.middleware.model.support.adapter.v1.coursedetail.curriculum.CurriculumAdapter;
import vn.edu.topica.eco.api.middleware.repository.CartRepository;
import vn.edu.topica.eco.api.middleware.repository.CourseRepository;
import vn.edu.topica.eco.api.middleware.repository.WishlistRepository;
import vn.edu.topica.eco.api.middleware.service.v1.CategoryService;
import vn.edu.topica.eco.api.middleware.service.v1.CourseService;
import vn.edu.topica.eco.api.middleware.service.v1.CustomerService;
import vn.edu.topica.eco.api.middleware.util.Utils;

import java.util.*;
import java.util.stream.Collectors;

import static vn.edu.topica.eco.api.middleware.constant.CacheConst.CacheName.*;
import static vn.edu.topica.eco.api.middleware.constant.LocalConst.MARKET_TYPE_MAP;
import static vn.edu.topica.eco.api.middleware.constant.MobileConst.*;

@Service
@Slf4j
public class CourseServiceImpl implements CourseService {

  private final CourseRepository courseRepository;
  private final CartRepository cartRepository;
  private final WishlistRepository wishlistRepository;
  private final CategoryService categoryService;
  private final CustomerService customerService;
  private final CacheManager cacheManager;

  public CourseServiceImpl(CourseRepository courseRepository,
                           CartRepository cartRepository,
                           WishlistRepository wishlistRepository,
                           CategoryService categoryService,
                           CustomerService customerService,
                           CacheManager cacheManager) {
    this.courseRepository = courseRepository;
    this.cartRepository = cartRepository;
    this.wishlistRepository = wishlistRepository;
    this.categoryService = categoryService;
    this.customerService = customerService;
    this.cacheManager = cacheManager;
  }
  
  /* ... */

  @Override
  public PersonalCourses getPersonalCourses(String token, String cartId) throws Exception {
    if (!Strings.isNullOrEmpty(token)) {
      int customerId = customerService.getCustomerIdFromToken(token);
      return tryGetDataFromCache(token, customerId);
    } else if (!Strings.isNullOrEmpty(cartId)) {
      PersonalCourses personalCourses = new PersonalCourses();
      MagentoCart magentoCart = (MagentoCart) cartRepository.getGuestCartTotalInfo(cartId).getData();

      personalCourses.setCartItems(
        Utils.transform(magentoCart.getItems(), item -> item.getExtensionAttributes().getSku())
      );
      return personalCourses;
    }
    return new PersonalCourses();
  }

  private PersonalCourses tryGetDataFromCache(String token,
                                              int customerId) throws Exception {
    PersonalCourses personalCourses = new PersonalCourses();
    getCacheDataOrGetDataFromMagento(personalCourses, COURSES_IN_CART, token, customerId);
    getCacheDataOrGetDataFromMagento(personalCourses, COURSES_IN_WISHLIST, token, customerId);
    getCacheDataOrGetDataFromMagento(personalCourses, COURSES_OWNED, token, customerId);
    return personalCourses;
  }

  private void getCacheDataOrGetDataFromMagento(PersonalCourses personalCourses,
                                                CacheConst.CacheName cacheName, String token, int customerId) throws Exception {
    Cache cache = cacheManager.getCache(cacheName.name());
    if (cache != null && cache.get(customerId, PersonalCourseItems.class) != null) {
      PersonalCourseItems items = cache.get(customerId, PersonalCourseItems.class);
      long expiredTime = items.getCachedTime() + CacheConst.PERSONAL_CACHE_TTL.toMillis();
      if (System.currentTimeMillis() > expiredTime) {
        log.info("Data from cache " + cacheName.name() + " is expired, try get new data from magento!!!");
        getDataFromMagentoThenPutToCache(cacheName, cache, personalCourses, customerId, token);
      } else {
        switch (cacheName) {
          case COURSES_IN_CART:
            personalCourses.setCartItems(items.getCourseIdentifies());
            log.info("get cart items from cache!!!");
            break;
          case COURSES_IN_WISHLIST:
            personalCourses.setWishlistItems(items.getCourseIdentifies());
            log.info("get wishlist items from cache!!!");
            break;
          case COURSES_OWNED:
            personalCourses.setOwnedItems(items.getCourseIdentifies());
            log.info("get owned items from cache!!!");
            break;
        }
      }
    } else {
      getDataFromMagentoThenPutToCache(cacheName, cache, personalCourses, customerId, token);
    }
  }

  @SuppressWarnings("unchecked")
  private void getDataFromMagentoThenPutToCache(CacheConst.CacheName cacheName,
                                                Cache cache, PersonalCourses personalCourses, int customerId, String token) throws Exception {
    List<String> courseIdentifies = null;
    switch (cacheName) {
      case COURSES_IN_CART:
        MagentoCart magentoCart = (MagentoCart) cartRepository
          .getCustomerCartTotalInfo(customerId).getData();
        courseIdentifies = Utils.transform(magentoCart.getItems(),
          item -> item.getExtensionAttributes().getSku());
        personalCourses.setCartItems(courseIdentifies);
        log.info("get in cart items from Magento!!!");
        break;
      case COURSES_IN_WISHLIST:
        List<MagentoWishlistItem> magentoWishlist = (List<MagentoWishlistItem>) wishlistRepository.getWishlist(customerId).getData();
        courseIdentifies = Utils.transform(magentoWishlist,
          item -> item.getMagentoCourse().getSku());
        personalCourses.setWishlistItems(courseIdentifies);
        log.info("get in wishlist items from Magento!!!");
        break;
      case COURSES_OWNED:
        courseIdentifies = (List<String>) courseRepository.getOwnedCourses(token).getData();
        personalCourses.setOwnedItems(courseIdentifies);
        log.info("get owned items from Magento!!!");
        break;
    }
    if (cache != null && courseIdentifies != null) {
      cache.put(customerId, new PersonalCourseItems(courseIdentifies, System.currentTimeMillis()));
      log.info("put personalCourseItems to cache" + cacheName.name() + courseIdentifies);
    }
  }

}
