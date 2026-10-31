package org.ipro.e5browser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.ipro.form.spi.*;
import org.ipro.rls.*;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.data.EntityDataAccess;
import org.ipro.metadata.ManagedEntityCatalog;
import com.example.generated.targets.ReferenceTarget;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.context.annotation.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
public class BrowserConfiguration {
    @Bean UserDetailsService users(){return new InMemoryUserDetailsManager(
        User.withUsername("e5-admin").password("{noop}e5-local-only").roles("ADMIN").build(),
        User.withUsername("e5-reader").password("{noop}e5-local-only").roles("USER").build());}
    @Bean SecurityFilterChain security(HttpSecurity http)throws Exception{
        return http.with(VaadinSecurityConfigurer.vaadin(),v->v.loginView(BrowserLogin.class)).build();
    }
    @Bean RlsCurrentUser current(){return ()->{var a=SecurityContextHolder.getContext().getAuthentication();return a==null?null:a.getName();};}
    @Bean RlsRoleResolver roles(){return user->List.of();}
    @Bean FormSettingsStore settings(){return new FormSettingsStore(){
        final Map<String,String> values=new ConcurrentHashMap<>();
        public Optional<String> get(String k){return Optional.ofNullable(values.get(k));}
        public void put(String k,String v){values.put(k,v);}public void remove(String k){values.remove(k);}
    };}
    @Bean GridViewStore views(){return new GridViewStore(){
        public List<GridView> findVisibleViews(String key){return List.of();}public Optional<GridView> findById(Long id){return Optional.empty();}
        public GridView createView(String f,String n,String c,boolean s){throw new UnsupportedOperationException();}
        public GridView updateView(GridView v){throw new UnsupportedOperationException();}public void deleteView(Long id){throw new UnsupportedOperationException();}
    };}
    @Bean CommandLineRunner seed(AccessGrantRepository grants, EntityDataAccess data){return args->{
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("e5-admin",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        for(String simple:BrowserView.TYPES){Class<?> t=Class.forName("com.example.generated."+simple);grant(grants,t,"e5-admin",true);grant(grants,t,"e5-reader",false);}
        grant(grants,ReferenceTarget.class,"e5-admin",true);grant(grants,ReferenceTarget.class,"e5-reader",false);
        for(String name:List.of("Контрагент A","Контрагент B")){var t=new ReferenceTarget();t.setName(name);data.create(ReferenceTarget.class,t);}
        SecurityContextHolder.clearContext();
    };}
    static void grant(AccessGrantRepository repo,Class<?> type,String user,boolean write){
        var g=new AccessGrant();g.setSubjectType(AccessGrant.SubjectType.USER);g.setSubjectKey(user);g.setDimension(C5PermissionKeys.entity(type));g.setCanRead(true);g.setCanUpdate(write);g.setCanDelete(write);repo.saveAndFlush(g);
    }
}
