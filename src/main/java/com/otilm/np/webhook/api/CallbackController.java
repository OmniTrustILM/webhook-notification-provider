package com.otilm.np.webhook.api;

import com.otilm.api.model.common.attribute.common.BaseAttribute;
import com.otilm.np.webhook.attribute.Attributes;
import com.otilm.np.webhook.attribute.ContentType;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/notificationProvider/callbacks")
public class CallbackController {

    @GetMapping(path = "/template/{contentType}/attributes", produces = "application/json")
    public List<BaseAttribute> getContentTemplateAttributes(@PathVariable ContentType contentType) {
        if (contentType == ContentType.RAW_JSON) {
            return List.of();
        }

        return List.of(Attributes.dataContentTemplate(contentType));
    }

}
